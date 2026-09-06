package site.syamdev.shush.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import site.syamdev.shush.config.NodeIdentity;

import java.time.Duration;

/**
 * Makes a scheduled job run on exactly one replica at a time.
 *
 * <p>Every node runs the same timers, so without this each tick would run three times -- and a
 * purge job running three times concurrently is not merely wasteful, it is three transactions
 * competing to delete the same rows.
 *
 * <p>Two details matter. The lock has a TTL, so a node that dies mid-job releases it by expiry
 * rather than blocking that job forever. And a tick that finds the lock held is <em>skipped</em>,
 * never queued: these jobs are periodic sweeps, so a missed run is corrected by the next one,
 * whereas a queue of them would pile up faster than they drain the moment one run is slow.
 *
 * <p>The lock is released only by the holder, identified by node id. Deleting it unconditionally
 * would let a node that had already overrun its TTL delete the lock a *different* node was
 * relying on, which is the classic way this pattern fails.
 */
@Component
public class JobLock {

    private static final Logger log = LoggerFactory.getLogger(JobLock.class);

    private final StringRedisTemplate redis;
    private final NodeIdentity node;
    private final Counter skipped;

    JobLock(StringRedisTemplate redis, NodeIdentity node, MeterRegistry meters) {
        this.redis = redis;
        this.node = node;
        this.skipped = Counter.builder("shush.jobs.skipped")
                .description("scheduled ticks skipped because another replica held the lock")
                .register(meters);
    }

    /**
     * @return true if the job ran here, false if another replica already had it
     */
    public boolean runIfNotHeld(String job, Duration lease, Runnable work) {
        String key = "lock:" + job;
        Boolean acquired = redis.opsForValue().setIfAbsent(key, node.nodeId(), lease);
        if (!Boolean.TRUE.equals(acquired)) {
            skipped.increment();
            return false;
        }

        try {
            work.run();
            return true;
        } catch (RuntimeException e) {
            log.error("scheduled job {} failed", job, e);
            return true;
        } finally {
            release(key);
        }
    }

    private void release(String key) {
        if (node.nodeId().equals(redis.opsForValue().get(key))) {
            redis.delete(key);
        }
    }
}
