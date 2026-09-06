package site.syamdev.shush.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.fail;

/** A real WebSocket client for integration tests. Nothing here is mocked. */
public class WsClient implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebSocketSession session;
    private final LinkedBlockingQueue<JsonNode> frames;

    private WsClient(WebSocketSession session, LinkedBlockingQueue<JsonNode> frames) {
        this.session = session;
        this.frames = frames;
    }

    public static WsClient connect(int port, String jwt) throws Exception {
        LinkedBlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession s, TextMessage message) throws IOException {
                        frames.add(JSON.readTree(message.getPayload()));
                    }
                }, null, URI.create("ws://localhost:" + port + "/ws/chat?token=" + jwt))
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        return new WsClient(session, frames);
    }

    public void send(String json) throws IOException {
        session.sendMessage(new TextMessage(json));
    }

    public void sendText(UUID conversationId, UUID clientMsgId, String body) throws IOException {
        send("{\"type\":\"send\",\"conversationId\":\"%s\",\"clientMsgId\":\"%s\",\"kind\":\"text\",\"body\":\"%s\"}"
                .formatted(conversationId, clientMsgId, body));
    }

    /** Waits for the next frame of this type, putting anything it passed over back afterwards. */
    public JsonNode await(String type) throws InterruptedException {
        return await(type, frame -> true, "type=" + type);
    }

    /** Waits for the next ack carrying this status -- sends are acked twice, sent then delivered. */
    public JsonNode awaitAck(String status) throws InterruptedException {
        return await("ack", frame -> status.equals(frame.path("status").asText()),
                "ack status=" + status);
    }

    public JsonNode await(String type, Predicate<JsonNode> matching, String description)
            throws InterruptedException {
        List<JsonNode> skipped = new ArrayList<>();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode frame = frames.poll(200, TimeUnit.MILLISECONDS);
            if (frame == null) {
                continue;
            }
            if (type.equals(frame.path("type").asText()) && matching.test(frame)) {
                frames.addAll(skipped);
                return frame;
            }
            skipped.add(frame);
        }
        frames.addAll(skipped);
        return fail("no frame matching %s arrived within %s; saw %s", description, TIMEOUT, skipped);
    }

    /** Every frame received so far, in arrival order, without consuming them. */
    public List<JsonNode> receivedSoFar() {
        return List.copyOf(frames);
    }

    /** Collects the next {@code count} frames of this type, in arrival order. */
    public List<JsonNode> awaitAll(String type, int count) throws InterruptedException {
        List<JsonNode> collected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            collected.add(await(type));
        }
        return collected;
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    @Override
    public void close() throws IOException {
        session.close(CloseStatus.NORMAL);
    }
}
