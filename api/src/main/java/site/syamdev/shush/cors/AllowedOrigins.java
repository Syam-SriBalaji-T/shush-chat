package site.syamdev.shush.cors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * The set of browser origins allowed to call this API, read from the database.
 *
 * <p>The point of the table is that adding a frontend does not need a deployment, so this is
 * read at request time rather than at startup. It is also on the path of every cross-origin
 * request including preflights, so it is cached for a few seconds — long enough that a burst
 * of requests costs one query, short enough that a new row is live before anyone has finished
 * wondering whether it worked.
 *
 * <p>Failing closed on a database error is deliberate. An empty set means no cross-origin
 * browser call is permitted, and that is the safe direction: the app and the API are served
 * from one origin through nginx, so a Postgres outage takes nothing down that was working.
 */
@Component
public class AllowedOrigins {

    private static final Logger log = LoggerFactory.getLogger(AllowedOrigins.class);

    private final CorsOriginRepository origins;
    private final long ttlMillis;

    private volatile Set<String> cached = Set.of();

    // Zero, not Long.MIN_VALUE: the freshness check subtracts this from the current time, and
    // currentTimeMillis() - Long.MIN_VALUE overflows to a negative number. That reads as "less
    // than the TTL", so the cache looks permanently fresh and the empty starting value is
    // never replaced -- every cross-origin request refused, for ever, with nothing logged.
    private final AtomicLong loadedAt = new AtomicLong(0);

    AllowedOrigins(CorsOriginRepository origins,
                   @Value("${shush.cors.cache-ttl:15s}") Duration ttl) {
        this.origins = origins;
        this.ttlMillis = ttl.toMillis();
    }

    public Set<String> current() {
        long now = System.currentTimeMillis();
        long previous = loadedAt.get();
        if (now - previous < ttlMillis) {
            return cached;
        }
        // Whoever wins the CAS refreshes; everyone else keeps serving the value they have
        // rather than piling onto the database behind a lock.
        if (!loadedAt.compareAndSet(previous, now)) {
            return cached;
        }
        try {
            cached = origins.findAll().stream()
                    .map(CorsOrigin::getOrigin)
                    .filter(origin -> origin != null && !origin.isBlank())
                    .map(String::trim)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException unavailable) {
            log.warn("could not read cors_origins; refusing cross-origin requests until it is readable");
            cached = Set.of();
        }
        return cached;
    }

    public boolean permits(String origin) {
        return origin != null && current().contains(origin);
    }

    public List<String> asList() {
        return List.copyOf(current());
    }
}
