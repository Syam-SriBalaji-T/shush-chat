package site.syamdev.shush.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import site.syamdev.shush.conversation.ConversationService;
import site.syamdev.shush.message.Message;

import java.util.UUID;

/** Phase 2: one node, so every recipient is either on this node or not connected at all. */
@Component
class LocalMessageDispatcher implements MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LocalMessageDispatcher.class);

    private final SessionRegistry registry;
    private final ConversationService conversations;
    private final ObjectMapper json;
    private final Counter fannedOut;

    LocalMessageDispatcher(SessionRegistry registry, ConversationService conversations,
                           ObjectMapper json, MeterRegistry meters) {
        this.registry = registry;
        this.conversations = conversations;
        this.json = json;
        this.fannedOut = Counter.builder("shush.messages.fanned.out")
                .description("message frames written to a websocket")
                .register(meters);
    }

    @Override
    public void deliver(Message message) {
        String payload = write(ServerFrame.MessageFrame.of(message));
        if (payload == null) {
            return;
        }
        for (UUID participantId : conversations.participantIds(message.getConversationId())) {
            // A recipient with no socket here is simply offline; the message is already durable
            // and history will hand it over on reconnect.
            fannedOut.increment(registry.sendTo(participantId, payload));
        }
    }

    @Override
    public void acknowledge(UUID senderId, Message message, boolean duplicate) {
        String payload = write(ServerFrame.Ack.delivered(message, duplicate));
        if (payload != null) {
            registry.sendTo(senderId, payload);
        }
    }

    private String write(ServerFrame frame) {
        try {
            return json.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            log.error("could not serialise a {} frame", frame.getClass().getSimpleName(), e);
            return null;
        }
    }
}
