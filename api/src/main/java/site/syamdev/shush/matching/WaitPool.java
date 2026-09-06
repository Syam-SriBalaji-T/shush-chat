package site.syamdev.shush.matching;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who is currently waiting to be matched, and — the part that matters — who gets them.
 *
 * <p>Two matchers can find the same third person at the same instant. Whichever of them writes
 * the conversation first is not the question; the question is that <em>both</em> must not.
 * {@link #claimBoth} is a Lua script, so Redis runs the "are both still waiting? then take both"
 * check and the removal as one indivisible step. A check followed by a separate removal would
 * leave a window between them, and that window is exactly where a person ends up in two
 * conversations at once.
 */
@Component
public class WaitPool {

    private static final Logger log = LoggerFactory.getLogger(WaitPool.class);

    static final String POOL_KEY = "matchpool";
    private static final String DETAIL_PREFIX = "matchwait:";
    private static final Duration DETAIL_TTL = Duration.ofHours(1);

    /**
     * Removes both members only if both are still present, and reports whether it did. Anything
     * less than atomic here is a race that hands one person to two matchers.
     */
    private static final RedisScript<Long> CLAIM_BOTH = new DefaultRedisScript<>("""
            if redis.call('ZSCORE', KEYS[1], ARGV[1]) and redis.call('ZSCORE', KEYS[1], ARGV[2]) then
              redis.call('ZREM', KEYS[1], ARGV[1], ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    WaitPool(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void enqueue(WaitingUser waiting) {
        redis.opsForZSet().add(POOL_KEY, waiting.userId().toString(),
                waiting.enqueuedAt().toEpochMilli());
        redis.opsForValue().set(detailKey(waiting.userId()), write(waiting), DETAIL_TTL);
    }

    public void remove(UUID userId) {
        redis.opsForZSet().remove(POOL_KEY, userId.toString());
        redis.delete(detailKey(userId));
    }

    public boolean isWaiting(UUID userId) {
        return redis.opsForZSet().score(POOL_KEY, userId.toString()) != null;
    }

    public Optional<WaitingUser> detailsOf(UUID userId) {
        String stored = redis.opsForValue().get(detailKey(userId));
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(stored, WaitingUser.class));
        } catch (JsonProcessingException e) {
            log.warn("discarding an unreadable wait-pool entry for {}", userId);
            remove(userId);
            return Optional.empty();
        }
    }

    /** @return true if this caller took both; false if someone else got there first */
    public boolean claimBoth(UUID first, UUID second) {
        Long claimed = redis.execute(CLAIM_BOTH, List.of(POOL_KEY),
                first.toString(), second.toString());
        return claimed != null && claimed == 1L;
    }

    /** The longest-waiting people, oldest first — the fallback when patience runs out. */
    public List<UUID> longestWaiting(int limit) {
        Set<String> ids = redis.opsForZSet().range(POOL_KEY, 0, limit - 1L);
        return ids == null ? List.of() : ids.stream().map(UUID::fromString).toList();
    }

    public long size() {
        Long size = redis.opsForZSet().zCard(POOL_KEY);
        return size == null ? 0 : size;
    }

    private String write(WaitingUser waiting) {
        try {
            return json.writeValueAsString(waiting);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise a wait-pool entry", e);
        }
    }

    private static String detailKey(UUID userId) {
        return DETAIL_PREFIX + userId;
    }
}
