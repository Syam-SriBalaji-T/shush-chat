package site.syamdev.shush.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import site.syamdev.shush.common.ApiException;
import site.syamdev.shush.conversation.ConversationService;
import site.syamdev.shush.message.Message;
import site.syamdev.shush.message.MessageService;

import java.io.IOException;
import java.util.UUID;

/**
 * Phase 1: one node, so fanout is a lookup in the local registry.
 *
 * <p>Phase 3 replaces that with a Redis publish for every recipient including same-node ones,
 * so there is exactly one delivery path. Two paths is how same-node quietly works while
 * cross-node is broken.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final SessionRegistry registry;
    private final ConversationService conversations;
    private final MessageService messages;
    private final ObjectMapper json;

    ChatWebSocketHandler(SessionRegistry registry, ConversationService conversations,
                         MessageService messages, ObjectMapper json) {
        this.registry = registry;
        this.conversations = conversations;
        this.messages = messages;
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
            MessageService.Append append = messages.append(send.conversationId(), senderId,
                    send.clientMsgId(), kind, send.body(), send.mediaKey());

            reply(session, ServerFrame.Ack.accepted(append.message(), append.duplicate()));

            // A duplicate was already fanned out when it first arrived. Sending it again is
            // exactly the reordering-and-repeats problem the dedup constraint exists to stop.
            if (!append.duplicate()) {
                fanout(append.message());
            }
        } catch (ApiException e) {
            reply(session, new ServerFrame.Error(e.getCode(), e.getMessage(), send.clientMsgId()));
        } catch (IllegalArgumentException e) {
            reply(session, new ServerFrame.Error("invalid_send", "unsupported message kind", send.clientMsgId()));
        }
    }

    private void fanout(Message message) throws IOException {
        String payload = json.writeValueAsString(ServerFrame.MessageFrame.of(message));
        for (UUID participantId : conversations.participantIds(message.getConversationId())) {
            registry.sendTo(participantId, payload);
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
