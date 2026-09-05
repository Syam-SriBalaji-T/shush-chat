package site.syamdev.shush.realtime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pings every locally held socket.
 *
 * <p>This is not one of the scheduled *jobs* from plan.md 3.10 and deliberately takes no Redis
 * lock: those must run on exactly one replica, whereas this maintains connections that only
 * this replica holds and must therefore run on all of them.
 */
@Component
class HeartbeatScheduler {

    private final SessionRegistry registry;

    HeartbeatScheduler(SessionRegistry registry) {
        this.registry = registry;
    }

    /** Comfortably inside nginx's 60s default, so an idle conversation is never dropped. */
    @Scheduled(fixedRateString = "${shush.websocket.heartbeat:30s}")
    void ping() {
        registry.pingAll();
    }
}
