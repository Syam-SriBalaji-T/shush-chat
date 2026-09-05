package site.syamdev.shush.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import site.syamdev.shush.conversation.ConversationService;
import site.syamdev.shush.message.Message;

import java.util.UUID;

/**
 * Every message and every writer ack leaves this node through Redis -- including when the
 * recipient is connected to this very node.
 *
 * <p>Short-circuiting the same-node case would be an easy optimisation and a bad one: it
 * creates two delivery paths, and the local one is the one that always works in development.
 * Cross-node delivery would then break silently and only show up under a load balancer. One
 * path means a bug is a bug everywhere.
 */
@Component
class RedisMessageDispatcher implements MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageDispatcher.class);

    private final StringRedisTemplate redis;
    private final ConversationService conversations;
    private final ObjectMapper json;
    private final Counter published;

    RedisMessageDispatcher(StringRedisTemplate redis, ConversationService conversations,
                           ObjectMapper json, MeterRegistry meters) {
        this.redis = redis;
        this.conversations = conversations;
        this.json = json;
        this.published = Counter.builder("shush.messages.published")
                .description("frames published to the backplane")
                .register(meters);
    }

    @Override
    public void deliver(Message message) {
        String payload = write(ServerFrame.MessageFrame.of(message));
        if (payload == null) {
            return;
        }
        for (UUID participantId : conversations.participantIds(message.getConversationId())) {
            // A recipient with no socket anywhere is simply offline. The message is already
            // durable and history hands it over on reconnect.
            publish(participantId, payload);
        }
    }

    @Override
    public void acknowledge(UUID senderId, Message message, boolean duplicate) {
        String payload = write(ServerFrame.Ack.delivered(message, duplicate));
        if (payload != null) {
            // The sender's socket may well be on a different node from the writer that
            // sequenced this message, so even an ack has to be routed rather than replied to.
            publish(senderId, payload);
        }
    }

    private void publish(UUID userId, String payload) {
        redis.convertAndSend(UserChannels.of(userId), payload);
        published.increment();
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
