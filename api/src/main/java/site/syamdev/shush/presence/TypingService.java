package site.syamdev.shush.presence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Typing indicators, throttled at the server.
 *
 * <p>A typing event fires on keystrokes, so this is by far the highest-frequency thing in the
 * protocol: a hundred people typing normally is several hundred writes a second for information
 * that is worthless three seconds later. The client is asked to send at most one every three
 * seconds, and the server enforces the same bound rather than trusting it -- a client with a bug,
 * or one written by someone else, would otherwise be able to melt the datastore.
 *
 * <p>The throttle is a separate short-lived key rather than a check of the indicator's own TTL,
 * because "has it been three seconds" and "is this person still typing" are different questions
 * with different answers, and SET NX settles the first one atomically.
 */
@Service
public class TypingService {

    private static final String INDICATOR_PREFIX = "typing:";
    private static final String THROTTLE_PREFIX = "typing-throttle:";

    private final StringRedisTemplate redis;
    private final Duration indicatorTtl;
    private final Duration throttle;

    TypingService(StringRedisTemplate redis,
                  @Value("${shush.typing.ttl}") Duration indicatorTtl,
                  @Value("${shush.typing.throttle}") Duration throttle) {
        this.redis = redis;
        this.indicatorTtl = indicatorTtl;
        this.throttle = throttle;
    }

    /**
     * @return true if this event should be forwarded; false if it arrived inside the throttle
     *         window and should simply be dropped
     */
    public boolean accept(UUID conversationId, UUID userId) {
        Boolean firstInWindow = redis.opsForValue()
                .setIfAbsent(throttleKey(conversationId, userId), "1", throttle);
        if (!Boolean.TRUE.equals(firstInWindow)) {
            return false;
        }
        redis.opsForValue().set(indicatorKey(conversationId, userId), "1", indicatorTtl);
        return true;
    }

    public boolean isTyping(UUID conversationId, UUID userId) {
        return Boolean.TRUE.equals(redis.hasKey(indicatorKey(conversationId, userId)));
    }

    /** Sending a message means you have stopped typing it. */
    public void clear(UUID conversationId, UUID userId) {
        redis.delete(indicatorKey(conversationId, userId));
    }

    private static String indicatorKey(UUID conversationId, UUID userId) {
        return INDICATOR_PREFIX + conversationId + ":" + userId;
    }

    private static String throttleKey(UUID conversationId, UUID userId) {
        return THROTTLE_PREFIX + conversationId + ":" + userId;
    }
}
