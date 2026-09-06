package site.syamdev.shush.realtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import site.syamdev.shush.conversation.ConversationService;
import site.syamdev.shush.message.Message;

import java.util.UUID;

/**
 * Every message and every writer ack leaves this node through Redis -- including when the
 * recipient is connected to this very node.
 *
 * <p>Short-circuiting the same-node case would be an easy optimisation and a bad one: it creates
 * two delivery paths, and the local one is the one that always works in development. Cross-node
 * delivery would then break silently and only show up under a load balancer. One path means a
 * bug is a bug everywhere.
 */
@Component
class RedisMessageDispatcher implements MessageDispatcher {

    private final BackplanePublisher backplane;
    private final ConversationService conversations;
    private final Counter delivered;

    RedisMessageDispatcher(BackplanePublisher backplane, ConversationService conversations,
                           MeterRegistry meters) {
        this.backplane = backplane;
        this.conversations = conversations;
        this.delivered = Counter.builder("shush.messages.delivered")
                .description("messages fanned out to their conversation's participants")
                .register(meters);
    }

    @Override
    public void deliver(Message message) {
        // A recipient with no socket anywhere is simply offline. The message is already durable
        // and the resume endpoint hands it over when they come back.
        backplane.publish(conversations.participantIds(message.getConversationId()),
                ServerFrame.MessageFrame.of(message));
        delivered.increment();
    }

    @Override
    public void acknowledge(UUID senderId, Message message, boolean duplicate) {
        // The sender's socket may well be on a different node from the writer that sequenced
        // this message, so even an ack has to be routed rather than replied to.
        backplane.publish(senderId, ServerFrame.Ack.delivered(message, duplicate));
    }
}
