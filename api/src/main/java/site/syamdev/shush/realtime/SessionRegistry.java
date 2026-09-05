package site.syamdev.shush.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which sockets this node holds, for which users. One user may have several -- a second tab is
 * a second session, not a replacement.
 *
 * <p>Node-local on purpose (aim.md 4.1): this is hot, read constantly, and inherently about
 * this process. Cross-node routing is a separate problem, solved by the Redis backplane in
 * Phase 3 rather than by externalising this map.
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<UUID, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    public void register(UUID userId, WebSocketSession session) {
        sessionsByUser.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(UUID userId, WebSocketSession session) {
        sessionsByUser.computeIfPresent(userId, (id, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    public Set<WebSocketSession> sessionsOf(UUID userId) {
        return sessionsByUser.getOrDefault(userId, Set.of());
    }

    public int openConnectionCount() {
        return sessionsByUser.values().stream().mapToInt(Set::size).sum();
    }

    /** @return how many sockets actually received the payload */
    public int sendTo(UUID userId, String payload) {
        int delivered = 0;
        for (WebSocketSession session : sessionsOf(userId)) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                // Spring's WebSocketSession is not safe for concurrent senders and fanout can
                // reach one socket from several threads at once.
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
                delivered++;
            } catch (IOException e) {
                log.debug("dropping frame for a session that went away: {}", session.getId());
            }
        }
        return delivered;
    }
}
