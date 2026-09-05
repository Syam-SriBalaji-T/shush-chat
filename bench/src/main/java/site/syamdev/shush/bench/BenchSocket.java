package site.syamdev.shush.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One participant's socket. It records every frame it receives in arrival order -- the arrival
 * order is the thing under test, so nothing here sorts, dedups or otherwise tidies the stream.
 */
final class BenchSocket implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebSocket socket;
    private final Queue<JsonNode> received;
    private final AtomicInteger messageFrames;
    private final AtomicReference<String> servedByNode;

    private BenchSocket(WebSocket socket, Queue<JsonNode> received,
                        AtomicInteger messageFrames, AtomicReference<String> servedByNode) {
        this.socket = socket;
        this.received = received;
        this.messageFrames = messageFrames;
        this.servedByNode = servedByNode;
    }

    static BenchSocket open(HttpClient http, String websocketUrl, String jwt) throws Exception {
        Queue<JsonNode> received = new ConcurrentLinkedQueue<>();
        AtomicInteger messageFrames = new AtomicInteger();
        AtomicReference<String> servedByNode = new AtomicReference<>();

        WebSocket socket = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create(websocketUrl + "?token=" + jwt), new WebSocket.Listener() {

                    private final StringBuilder partial = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            JsonNode frame = parse(partial.toString());
                            partial.setLength(0);
                            if (frame != null) {
                                received.add(frame);
                                if ("message".equals(frame.path("type").asText())) {
                                    messageFrames.incrementAndGet();
                                }
                                String node = frame.path("nodeId").asText(null);
                                if (node != null) {
                                    servedByNode.set(node);
                                }
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(30, TimeUnit.SECONDS);

        return new BenchSocket(socket, received, messageFrames, servedByNode);
    }

    void sendText(UUID conversationId, UUID clientMsgId, String body) {
        String frame = "{\"type\":\"send\",\"conversationId\":\"" + conversationId
                + "\",\"clientMsgId\":\"" + clientMsgId
                + "\",\"kind\":\"text\",\"body\":\"" + body + "\"}";
        // sendText returns a future that must complete before the next send on the same socket;
        // the WebSocket API rejects an overlapping send outright.
        socket.sendText(frame, true).join();
    }

    int messageFrameCount() {
        return messageFrames.get();
    }

    String servedByNode() {
        return servedByNode.get();
    }

    List<JsonNode> framesOfType(String type) {
        return received.stream().filter(frame -> type.equals(frame.path("type").asText())).toList();
    }

    List<JsonNode> allFrames() {
        return List.copyOf(received);
    }

    @Override
    public void close() {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** Small helper so callers can wait on frame counts without sleeping in a loop by hand. */
    static boolean awaitQuiescence(List<BenchSocket> sockets, int expectedPerSocket, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        CountDownLatch tick = new CountDownLatch(1);
        while (System.nanoTime() < deadline) {
            boolean allArrived = sockets.stream()
                    .allMatch(socket -> socket.messageFrameCount() >= expectedPerSocket);
            if (allArrived) {
                return true;
            }
            tick.await(20, TimeUnit.MILLISECONDS);
        }
        return false;
    }
}
