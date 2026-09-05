package site.syamdev.shush.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import site.syamdev.shush.common.ApiException;
import site.syamdev.shush.conversation.ConversationService;
import site.syamdev.shush.message.ChatMessageProducer;
import site.syamdev.shush.message.Message;

import java.io.IOException;
import java.util.UUID;

/**
 * Accepts frames and produces to the log. It deliberately does not write to {@code messages}:
 * a controller that inserts is the dual write this design exists to remove, and it would
 * reintroduce the concurrent-writer reordering the partition key prevents.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final SessionRegistry registry;
    private final ConversationService conversations;
    private final ChatMessageProducer producer;
    private final ObjectMapper json;

    ChatWebSocketHandler(SessionRegistry registry, ConversationService conversations,
                         ChatMessageProducer producer, ObjectMapper json) {
        this.registry = registry;
        this.conversations = conversations;
        this.producer = producer;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.register(userId(session), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(userId(session), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage frame) throws IOException {
        UUID senderId = userId(session);
        ClientFrame parsed;
        try {
            parsed = json.readValue(frame.getPayload(), ClientFrame.class);
        } catch (IOException malformed) {
            reply(session, new ServerFrame.Error("malformed_frame", "could not parse this frame", null));
            return;
        }

        switch (parsed) {
            case ClientFrame.Send send -> handleSend(session, senderId, send);
        }
    }

    private void handleSend(WebSocketSession session, UUID senderId, ClientFrame.Send send) throws IOException {
        if (send.conversationId() == null || send.clientMsgId() == null) {
            reply(session, new ServerFrame.Error("invalid_send",
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

            reply(session, ServerFrame.Ack.sent(send.clientMsgId()));
        } catch (ApiException e) {
            reply(session, new ServerFrame.Error(e.getCode(), e.getMessage(), send.clientMsgId()));
        } catch (IllegalArgumentException e) {
            reply(session, new ServerFrame.Error("invalid_send", "unsupported message kind", send.clientMsgId()));
        } catch (RuntimeException e) {
            reply(session, new ServerFrame.Error("produce_failed",
                    "the message log did not accept this message", send.clientMsgId()));
        }
    }

    private void reply(WebSocketSession session, ServerFrame frame) throws IOException {
        synchronized (session) {
            session.sendMessage(new TextMessage(json.writeValueAsString(frame)));
        }
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
