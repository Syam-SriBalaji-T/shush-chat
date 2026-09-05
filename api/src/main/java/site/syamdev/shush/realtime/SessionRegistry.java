package site.syamdev.shush.realtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import site.syamdev.shush.config.NodeIdentity;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which sockets this node holds, for which users. One user may have several -- a second tab is
 * a second session, not a replacement.
 *
 * <p>Node-local on purpose (aim.md 4.1): this is hot, read constantly, and inherently about
 * this process. It is a routing table for the *last hop only*; deciding which node a user is on
 * is not its job, and never becomes its job -- that is what the backplane is for.
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<UUID, Map<String, WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    SessionRegistry(MeterRegistry meters, NodeIdentity node) {
        // Per node, not aggregated: whether connections are actually spread across replicas is
        // the thing worth seeing, and a sum would hide exactly that.
        Gauge.builder("shush.websocket.connections", this, SessionRegistry::openConnectionCount)
                .description("websocket sessions held by this node")
                .tag("node", node.nodeId())
                .register(meters);
    }

    /** @return true if this is the user's first session on this node */
    public boolean register(UUID userId, WebSocketSession session) {
        boolean[] first = {false};
        sessionsByUser.compute(userId, (id, sessions) -> {
            if (sessions == null) {
                first[0] = true;
                sessions = new ConcurrentHashMap<>();
            }
            sessions.put(session.getId(), session);
            return sessions;
        });
        return first[0];
    }

    /** @return true if that was the user's last session on this node */
    public boolean unregister(UUID userId, String sessionId) {
        boolean[] last = {false};
        sessionsByUser.computeIfPresent(userId, (id, sessions) -> {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                last[0] = true;
                return null;
            }
            return sessions;
        });
        return last[0];
    }

    public Collection<WebSocketSession> sessionsOf(UUID userId) {
        return sessionsByUser.getOrDefault(userId, Map.of()).values();
    }

    public Set<UUID> connectedUserIds() {
        return Set.copyOf(sessionsByUser.keySet());
    }

    public List<WebSocketSession> allSessions() {
        return sessionsByUser.values().stream().flatMap(sessions -> sessions.values().stream()).toList();
    }

    public int openConnectionCount() {
        return sessionsByUser.values().stream().mapToInt(Map::size).sum();
    }

    /** @return how many sockets actually received the payload */
    public int sendTo(UUID userId, String payload) {
        int delivered = 0;
        for (WebSocketSession session : sessionsOf(userId)) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(new TextMessage(payload));
                delivered++;
            } catch (IOException | IllegalStateException e) {
                // Gone, or over its buffer limit and already being torn down. Either way the
                // message is durable and history will hand it over when the client returns.
                log.debug("dropping a frame for session {}: {}", session.getId(), e.getClass().getSimpleName());
            }
        }
        return delivered;
    }

    /**
     * Application-level keepalive. nginx closes an idle proxied socket after
     * {@code proxy_read_timeout}, and a conversation that is quiet for a minute is completely
     * normal -- so the connection has to prove it is alive rather than rely on traffic.
     */
    public void pingAll() {
        for (Map.Entry<UUID, Map<String, WebSocketSession>> entry : sessionsByUser.entrySet()) {
            for (WebSocketSession session : List.copyOf(entry.getValue().values())) {
                if (!session.isOpen()) {
                    continue;
                }
                try {
                    session.sendMessage(new PingMessage());
                } catch (IOException | IllegalStateException e) {
                    log.debug("ping failed for session {}, dropping it", session.getId());
                    unregister(entry.getKey(), session.getId());
                }
            }
        }
    }
}
