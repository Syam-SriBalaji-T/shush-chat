package site.syamdev.shush.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.syamdev.shush.realtime.SessionRegistry;

import java.util.UUID;

/**
 * Retries matching for the people this node is holding sockets for.
 *
 * <p>Deliberately not one of the locked jobs in plan.md 3.10, and deliberately unlocked: every
 * replica ticks, for its own users only. That is safe because the claim itself is atomic -- two
 * nodes finding the same third person is the expected case, and exactly one of them wins. A
 * lock here would serialise the whole product's matching through one replica to prevent a race
 * that is already prevented.
 */
@Component
class MatchTicker {

    private static final Logger log = LoggerFactory.getLogger(MatchTicker.class);

    private final SessionRegistry registry;
    private final WaitPool pool;
    private final MatchingService matching;

    MatchTicker(SessionRegistry registry, WaitPool pool, MatchingService matching) {
        this.registry = registry;
        this.pool = pool;
        this.matching = matching;
    }

    @Scheduled(fixedRateString = "${shush.matching.tick}")
    void tick() {
        for (UUID userId : registry.connectedUserIds()) {
            pool.detailsOf(userId).ifPresent(waiting -> {
                try {
                    matching.attempt(waiting);
                } catch (RuntimeException e) {
                    log.warn("a matching attempt failed for {}", userId, e);
                }
            });
        }
    }
}
