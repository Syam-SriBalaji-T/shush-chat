package site.syamdev.shush.matching;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import site.syamdev.shush.support.AbstractIT;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single race that matters in matching: two people looking for someone at the same instant
 * can both be shown the same third person, and exactly one of them must get them.
 *
 * <p>Losing is normal and cheap -- the loser stays in the pool and tries again on the next tick.
 * Both winning is not recoverable: one person ends up in two conversations at once, and the
 * stranger they were promised is talking to somebody else.
 */
class ConcurrentClaimIT extends AbstractIT {

    @Autowired
    private WaitPool pool;

    @RepeatedTest(100)
    void twoMatchersNeverClaimTheSameThirdUser() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID contested = UUID.randomUUID();

        Instant now = Instant.now();
        pool.enqueue(new WaitingUser(first, List.of((short) 1), 0, now));
        pool.enqueue(new WaitingUser(second, List.of((short) 1), 0, now));
        pool.enqueue(new WaitingUser(contested, List.of((short) 1), 0, now));

        AtomicInteger winners = new AtomicInteger();
        CountDownLatch startTogether = new CountDownLatch(1);

        List<Callable<Boolean>> attempts = List.of(
                claim(startTogether, first, contested, winners),
                claim(startTogether, second, contested, winners));

        try (ExecutorService pool2 = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = new ArrayList<>();
            attempts.forEach(attempt -> futures.add(pool2.submit(attempt)));
            startTogether.countDown();
            for (Future<Boolean> future : futures) {
                future.get();
            }
        }

        assertThat(winners.get())
                .as("exactly one matcher may take the contested user")
                .isEqualTo(1);
        assertThat(pool.isWaiting(contested))
                .as("the contested user was taken, so is no longer waiting")
                .isFalse();

        pool.remove(first);
        pool.remove(second);
        pool.remove(contested);
    }

    @Test
    void claimingSomeoneWhoHasAlreadyGoneFails() {
        UUID here = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        pool.enqueue(new WaitingUser(here, List.of((short) 1), 0, Instant.now()));

        assertThat(pool.claimBoth(here, gone))
                .as("both must still be waiting, or neither is taken")
                .isFalse();
        assertThat(pool.isWaiting(here))
                .as("a failed claim must not remove the one who was still there")
                .isTrue();

        pool.remove(here);
    }

    private Callable<Boolean> claim(CountDownLatch startTogether, UUID matcher, UUID contested,
                                    AtomicInteger winners) {
        return () -> {
            startTogether.await();
            boolean won = pool.claimBoth(matcher, contested);
            if (won) {
                winners.incrementAndGet();
            }
            return won;
        };
    }
}
