package site.syamdev.shush.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import site.syamdev.shush.auth.AuthService;
import site.syamdev.shush.support.AbstractIT;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class NameAllocationIT extends AbstractIT {

    private static final int CONCURRENT_ALLOCATIONS = 1_000;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository users;

    @Autowired
    private NameAllocator nameAllocator;

    /**
     * 1,000 names out of a 40,000-wide space collides around a dozen times by birthday
     * arithmetic, so this genuinely exercises the retry path rather than the happy one.
     */
    @Test
    void namesNeverCollideAcrossAThousandConcurrentAllocations() throws Exception {
        assertThat(nameAllocator.combinationCount()).isEqualTo(40_000);

        long before = users.count();

        List<Callable<String>> allocations = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_ALLOCATIONS; i++) {
            allocations.add(() -> authService.createAnonymous().user().getDisplayName());
        }

        List<String> allocated;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = pool.invokeAll(allocations);
            allocated = new ArrayList<>();
            for (Future<String> future : futures) {
                allocated.add(future.get());
            }
        }

        Set<String> distinct = allocated.stream().collect(Collectors.toSet());
        assertThat(allocated).hasSize(CONCURRENT_ALLOCATIONS);
        assertThat(distinct).hasSize(CONCURRENT_ALLOCATIONS);
        assertThat(users.count()).isEqualTo(before + CONCURRENT_ALLOCATIONS);
    }
}
