package site.syamdev.shush.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One participant's socket, which reconnects when the node holding it dies.
 *
 * <p>It records every frame it receives in arrival order. Arrival order is the thing under test,
 * so nothing here sorts, deduplicates or otherwise tidies the stream -- doing so would hide
 * exactly the failure the harness exists to detect.
 */
final class BenchSocket implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final String websocketUrl;
    private final String jwt;

    private final Queue<JsonNode> received = new ConcurrentLinkedQueue<>();
    private final AtomicInteger messageFrames = new AtomicInteger();
    private final AtomicInteger reconnects = new AtomicInteger();
    private final AtomicInteger retransmissions = new AtomicInteger();
    private final Set<String> nodesServed = ConcurrentHashMap.newKeySet();
    private final Set<String> acked = ConcurrentHashMap.newKeySet();
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicBoolean live = new AtomicBoolean();

    private BenchSocket(HttpClient http, String websocketUrl, String jwt) {
        this.http = http;
        this.websocketUrl = websocketUrl;
        this.jwt = jwt;
    }

    static BenchSocket open(HttpClient http, String websocketUrl, String jwt) throws Exception {
        BenchSocket benchSocket = new BenchSocket(http, websocketUrl, jwt);
        benchSocket.connect();
        return benchSocket;
    }

    private void connect() throws Exception {
        WebSocket connected = http.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .buildAsync(URI.create(websocketUrl + "?token=" + jwt), new FrameListener())
                .get(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        socket.set(connected);
        live.set(true);
    }

    /**
     * Fire and forget: writes the frame and does not wait to hear that it landed. Used by the
     * ordering mode, where nothing is being killed, so a failed write is a real failure rather
     * than something to retry away.
     */
    boolean sendText(UUID conversationId, UUID clientMsgId, String body, Duration giveUpAfter) {
        String frame = "{\"type\":\"send\",\"conversationId\":\"" + conversationId
                + "\",\"clientMsgId\":\"" + clientMsgId
                + "\",\"kind\":\"text\",\"body\":\"" + body + "\"}";

        // Always attempt once, then retry until the deadline. A zero budget means "try once,
        // do not retry" -- which is what the ordering mode wants, since nothing is being killed
        // there and a failed send is a real failure rather than something to paper over.
        long deadline = System.nanoTime() + giveUpAfter.toNanos();
        while (true) {
            try {
                WebSocket current = socket.get();
                if (!live.get() || current == null || current.isOutputClosed()) {
                    throw new IOException("socket is not usable");
                }
                // The future must complete before the next send on the same socket; the
                // WebSocket API rejects an overlapping send outright.
                current.sendText(frame, true).join();
                return true;
            } catch (RuntimeException | IOException failed) {
                if (System.nanoTime() >= deadline || !reconnect(deadline)) {
                    return false;
                }
            }
        }
    }

    /**
     * Sends and waits to hear that the log accepted it, retransmitting the <em>same</em>
     * clientMsgId until it does.
     *
     * <p>This is what a correct client has to do, and it is the only honest way to test the
     * failure. A socket write succeeding proves nothing: the node can die between reading the
     * frame and producing it, and that message really is lost -- durability starts at the ack,
     * not at the send. So an unacked message is retransmitted, and because the id is stable the
     * server's dedup constraint is what stops the retry becoming a second message. A harness
     * that generated a fresh id on retry, or that counted a socket write as a send, would be
     * quietly testing nothing.
     */
    boolean sendAndAwaitAck(UUID conversationId, UUID clientMsgId, String body, Duration giveUpAfter) {
        String frame = frameFor(conversationId, clientMsgId, body);
        String id = clientMsgId.toString();
        long deadline = System.nanoTime() + giveUpAfter.toNanos();

        while (System.nanoTime() < deadline) {
            boolean written = writeOnce(frame);
            if (written && awaitAck(id, deadline)) {
                return true;
            }
            if (acked.contains(id)) {
                return true;
            }
            if (!reconnect(deadline)) {
                return acked.contains(id);
            }
            retransmissions.incrementAndGet();
        }
        return acked.contains(id);
    }

    private boolean writeOnce(String frame) {
        try {
            WebSocket current = socket.get();
            if (!live.get() || current == null || current.isOutputClosed()) {
                return false;
            }
            current.sendText(frame, true).join();
            return true;
        } catch (RuntimeException failed) {
            return false;
        }
    }

    private boolean awaitAck(String clientMsgId, long deadline) {
        long waitUntil = Math.min(deadline, System.nanoTime() + ACK_TIMEOUT.toNanos());
        while (System.nanoTime() < waitUntil) {
            if (acked.contains(clientMsgId)) {
                return true;
            }
            if (!live.get()) {
                return false;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return acked.contains(clientMsgId);
    }

    private String frameFor(UUID conversationId, UUID clientMsgId, String body) {
        return "{\"type\":\"send\",\"conversationId\":\"" + conversationId
                + "\",\"clientMsgId\":\"" + clientMsgId
                + "\",\"kind\":\"text\",\"body\":\"" + body + "\"}";
    }

    private boolean reconnect(long deadline) {
        live.set(false);
        while (System.nanoTime() < deadline) {
            try {
                connect();
                reconnects.incrementAndGet();
                return true;
            } catch (Exception stillDown) {
                try {
                    // nginx needs a moment to mark the dead replica down and route elsewhere.
                    TimeUnit.MILLISECONDS.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    int messageFrameCount() {
        return messageFrames.get();
    }

    int reconnectCount() {
        return reconnects.get();
    }

    int retransmissionCount() {
        return retransmissions.get();
    }

    Set<String> nodesServed() {
        return Set.copyOf(nodesServed);
    }

    List<JsonNode> framesOfType(String type) {
        return received.stream().filter(frame -> type.equals(frame.path("type").asText())).toList();
    }

    @Override
    public void close() {
        WebSocket current = socket.get();
        if (current != null && !current.isOutputClosed()) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    /** Waits until every socket has seen at least {@code expectedPerSocket} message frames. */
    static boolean awaitQuiescence(List<BenchSocket> sockets, int expectedPerSocket, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        CountDownLatch tick = new CountDownLatch(1);
        while (System.nanoTime() < deadline) {
            if (sockets.stream().allMatch(socket -> socket.messageFrameCount() >= expectedPerSocket)) {
                return true;
            }
            tick.await(20, TimeUnit.MILLISECONDS);
        }
        return false;
    }

    private final class FrameListener implements WebSocket.Listener {

        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                record(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            live.set(false);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            live.set(false);
        }

        private void record(String text) {
            JsonNode frame;
            try {
                frame = JSON.readTree(text);
            } catch (Exception malformed) {
                return;
            }
            received.add(frame);
            String type = frame.path("type").asText();
            if ("message".equals(type)) {
                messageFrames.incrementAndGet();
            } else if ("hello".equals(type)) {
                nodesServed.add(frame.path("nodeId").asText());
            } else if ("ack".equals(type)) {
                // "sent" already means the log accepted it and it cannot be lost, so there is
                // no reason to wait for "delivered" before considering the send done.
                acked.add(frame.path("clientMsgId").asText());
            }
        }
    }
}
