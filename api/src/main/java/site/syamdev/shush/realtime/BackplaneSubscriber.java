package site.syamdev.shush.realtime;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Subscribes this node to a user's channel while it holds one of their sockets, and drops the
 * subscription when the last one closes. A node listens only for the users actually connected
 * to it, so adding replicas does not multiply backplane traffic.
 *
 * <p>Subscriptions are reference-counted by the session registry, because a user may open
 * several tabs against the same node: the second tab must not create a second subscription and
 * closing it must not silence the first.
 */
@Component
class BackplaneSubscriber {

    private static final Logger log = LoggerFactory.getLogger(BackplaneSubscriber.class);

    /**
     * Registration is not thread-safe in the container's lazy-start path. Two sockets opening in
     * the same millisecond both call in, the first triggers the subscription connection, and the
     * second is registered against a connection that does not exist yet -- so it is silently
     * never subscribed and that user simply stops receiving messages. Serialising registration
     * means the connection is always established before a second topic is added.
     */
    private final ReentrantLock registration = new ReentrantLock();

    private final RedisMessageListenerContainer container;
    private final SessionRegistry registry;
    private final OrderedUserDelivery delivery;
    private final Map<UUID, MessageListener> listeners = new ConcurrentHashMap<>();

    BackplaneSubscriber(RedisMessageListenerContainer container, SessionRegistry registry,
                        OrderedUserDelivery delivery) {
        this.container = container;
        this.registry = registry;
        this.delivery = delivery;
    }

    /**
     * Establishes the subscription connection at startup, so the first real subscriber is not
     * the one paying for -- and racing with -- connection setup.
     */
    @PostConstruct
    void warmUpSubscriptionConnection() {
        container.addMessageListener((message, pattern) -> {
        }, new ChannelTopic("shush:backplane:warmup"));
    }

    void subscribe(UUID userId) {
        registration.lock();
        try {
            listeners.computeIfAbsent(userId, id -> {
                MessageListener listener = (message, pattern) -> {
                    String payload = new String(message.getBody(), StandardCharsets.UTF_8);
                    // Enqueue rather than write here: the receiving thread is shared by every
                    // channel this node listens to and must not block on one slow socket.
                    delivery.inOrder(id, () -> registry.sendTo(id, payload));
                };
                container.addMessageListener(listener, new ChannelTopic(UserChannels.of(id)));
                log.debug("subscribed to the channel for user {}", id);
                return listener;
            });
        } finally {
            registration.unlock();
        }
    }

    void unsubscribe(UUID userId) {
        registration.lock();
        try {
            MessageListener listener = listeners.remove(userId);
            if (listener != null) {
                container.removeMessageListener(listener, new ChannelTopic(UserChannels.of(userId)));
                delivery.forget(userId);
                log.debug("unsubscribed from the channel for user {}", userId);
            }
        } finally {
            registration.unlock();
        }
    }
}
