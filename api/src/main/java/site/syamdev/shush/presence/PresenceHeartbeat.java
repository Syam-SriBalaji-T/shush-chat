package site.syamdev.shush.presence;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.syamdev.shush.realtime.SessionRegistry;

import java.util.UUID;

/**
 * Renews the presence key for every user this node is holding a socket for.
 *
 * <p>Like the socket ping, this is per-node maintenance rather than one of the scheduled jobs
 * in plan.md 3.10, so it takes no Redis lock and must run on every replica: each one is the
 * only thing that knows who it is holding.
 */
@Component
class PresenceHeartbeat {

    private final SessionRegistry registry;
    private final PresenceService presence;

    PresenceHeartbeat(SessionRegistry registry, PresenceService presence) {
        this.registry = registry;
        this.presence = presence;
    }

    /** Comfortably inside the presence TTL, so a single missed tick never marks anyone away. */
    @Scheduled(fixedRateString = "${shush.presence.heartbeat}")
    void refresh() {
        for (UUID userId : registry.connectedUserIds()) {
            presence.refresh(userId);
        }
    }
}
