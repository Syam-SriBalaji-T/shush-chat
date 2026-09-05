package site.syamdev.shush.presence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import site.syamdev.shush.config.NodeIdentity;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is online, in Redis with a TTL.
 *
 * <p>The TTL is the whole design. Presence is high-write and low-value, and the failure that
 * matters is a node dying: without expiry, every user it held would stay "online" forever with
 * nothing left alive to correct the record. A key that has to be renewed to stay true cannot
 * outlive the process renewing it, so a crash is self-healing and no cleanup job is required.
 *
 * <p>The value is the node id, which is diagnostic only. Nothing routes by it -- routing goes
 * through the backplane, which is what makes any node able to serve any user.
 */
@Service
public class PresenceService {

    private static final String KEY_PREFIX = "presence:";

    private final StringRedisTemplate redis;
    private final NodeIdentity node;
    private final Duration ttl;

    PresenceService(StringRedisTemplate redis, NodeIdentity node,
                    @Value("${shush.presence.ttl}") Duration ttl) {
        this.redis = redis;
        this.node = node;
        this.ttl = ttl;
    }

    public void markOnline(UUID userId) {
        redis.opsForValue().set(key(userId), node.nodeId(), ttl);
    }

    /**
     * Called on a timer well inside the TTL. Renewing rather than extending indefinitely means
     * a node that stops running stops asserting anything about its users.
     */
    public void refresh(UUID userId) {
        markOnline(userId);
    }

    /**
     * Removed immediately on a clean disconnect so the other person is told promptly. The TTL
     * is the backstop for the case where nothing gets to run at all.
     */
    public void markOffline(UUID userId) {
        redis.delete(key(userId));
    }

    public boolean isOnline(UUID userId) {
        return Boolean.TRUE.equals(redis.hasKey(key(userId)));
    }

    /**
     * One round trip for a whole friends list. Asking per friend would turn opening the list
     * into N network calls, which is how presence becomes the slowest screen in the product.
     */
    public Map<UUID, Boolean> onlineAmong(List<UUID> userIds) {
        Map<UUID, Boolean> online = new HashMap<>();
        if (userIds.isEmpty()) {
            return online;
        }
        List<String> values = redis.opsForValue().multiGet(userIds.stream().map(PresenceService::key).toList());
        for (int i = 0; i < userIds.size(); i++) {
            online.put(userIds.get(i), values != null && values.get(i) != null);
        }
        return online;
    }

    private static String key(UUID userId) {
        return KEY_PREFIX + userId;
    }
}
