package site.syamdev.shush.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import site.syamdev.shush.common.ApiException;
import site.syamdev.shush.config.NodeIdentity;
import site.syamdev.shush.conversation.ConversationService;
import site.syamdev.shush.message.ChatMessageProducer;
import site.syamdev.shush.message.Message;

import java.io.IOException;
import java.util.UUID;

/**
 * Accepts frames and produces to the log. It deliberately does not write to {@code messages}: a
 * handler that inserts is the dual write this design exists to remove, and it would reintroduce
 * the concurrent-writer reordering the partition key prevents.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final SessionRegistry registry;
    private final BackplaneSubscriber backplane;
    private final ConversationService conversations;
    private final ChatMessageProducer producer;
    private final NodeIdentity node;
    private final ObjectMapper json;
    private final int sendTimeLimitMillis;
    private final int bufferSizeLimitBytes;

    ChatWebSocketHandler(SessionRegistry registry, BackplaneSubscriber backplane,
                         ConversationService conversations, ChatMessageProducer producer,
                         NodeIdentity node, ObjectMapper json,
                         @Value("${shush.websocket.send-time-limit-millis}") int sendTimeLimitMillis,
                         @Value("${shush.websocket.buffer-size-limit-bytes}") int bufferSizeLimitBytes) {
        this.registry = registry;
        this.backplane = backplane;
        this.conversations = conversations;
        this.producer = producer;
        this.node = node;
        this.json = json;
        this.sendTimeLimitMillis = sendTimeLimitMillis;
        this.bufferSizeLimitBytes = bufferSizeLimitBytes;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws IOException {
        UUID userId = userId(raw);

        // A client on a bad network cannot drain frames as fast as a busy conversation produces
        // them. Without a bound the server buffers for it until the heap runs out, so a session
        // that exceeds the limit is terminated instead: it can reconnect and re-sync from
        // history, which is cheap, whereas an OOM takes every other user on this node with it.
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
                raw, sendTimeLimitMillis, bufferSizeLimitBytes,
                ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);

        // Subscribe before announcing the socket is usable, so nothing published between the
        // two is missed.
        if (registry.register(userId, session)) {
            backplane.subscribe(userId);
        }
        send(session, new ServerFrame.Hello(userId, node.nodeId()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID userId = userId(session);
        if (registry.unregister(userId, session.getId())) {
            backplane.unsubscribe(userId);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage frame) throws IOException {
        UUID senderId = userId(session);
        ClientFrame parsed;
        try {
            parsed = json.readValue(frame.getPayload(), ClientFrame.class);
        } catch (IOException malformed) {
            replyTo(senderId, new ServerFrame.Error("malformed_frame", "could not parse this frame", null));
            return;
        }

        switch (parsed) {
            case ClientFrame.Send send -> handleSend(senderId, send);
        }
    }

    private void handleSend(UUID senderId, ClientFrame.Send send) {
        if (send.conversationId() == null || send.clientMsgId() == null) {
            replyTo(senderId, new ServerFrame.Error("invalid_send",
                    "conversationId and clientMsgId are both required", send.clientMsgId()));
            return;
        }

        try {
            conversations.requireParticipant(send.conversationId(), senderId);
            Message.Kind kind = send.kind() == null ? Message.Kind.TEXT : Message.Kind.fromWire(send.kind());

            // Block until the broker has acknowledged. Acking "sent" before the log accepted it
            // would be a lie the client cannot detect, and on a virtual thread the wait costs a
            // carrier thread nothing.
            producer.produce(send.conversationId(), senderId, send.clientMsgId(),
                    kind, send.body(), send.mediaKey()).join();

            replyTo(senderId, ServerFrame.Ack.sent(send.clientMsgId()));
        } catch (ApiException e) {
            replyTo(senderId, new ServerFrame.Error(e.getCode(), e.getMessage(), send.clientMsgId()));
        } catch (IllegalArgumentException e) {
            replyTo(senderId, new ServerFrame.Error("invalid_send", "unsupported message kind", send.clientMsgId()));
        } catch (RuntimeException e) {
            log.warn("produce failed for conversation {}", send.conversationId(), e);
            replyTo(senderId, new ServerFrame.Error("produce_failed",
                    "the message log did not accept this message", send.clientMsgId()));
        }
    }

    /**
     * Immediate replies -- {@code hello}, {@code sent}, protocol errors -- go straight to this
     * node's sockets for the user. They are answers to a frame that arrived here, not fanout,
     * so routing them through the backplane would add a hop and prove nothing. Anything
     * originating in the writer takes the backplane, because the writer may be another node.
     */
    private void replyTo(UUID userId, ServerFrame frame) {
        try {
            registry.sendTo(userId, json.writeValueAsString(frame));
        } catch (IOException e) {
            log.error("could not serialise a {} frame", frame.getClass().getSimpleName(), e);
        }
    }

    private void send(WebSocketSession session, ServerFrame frame) throws IOException {
        session.sendMessage(new TextMessage(json.writeValueAsString(frame)));
    }

    private static UUID userId(WebSocketSession session) {
        Object userId = session.getAttributes().get(JwtHandshakeInterceptor.USER_ID_ATTRIBUTE);
        if (userId instanceof UUID id) {
            return id;
        }
        // Unreachable: the handshake interceptor rejects the upgrade without a valid token.
        throw new IllegalStateException("websocket session opened without an authenticated user");
    }
}
