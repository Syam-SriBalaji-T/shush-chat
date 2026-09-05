package site.syamdev.shush.realtime;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Writes frames to a user's sockets in the order they were received from the backplane.
 *
 * <p>This exists because of a real defect the load harness caught: the backplane container
 * dispatches each received message on its own thread, so two frames for one conversation could
 * be written to a socket in either order. Sequence numbers stayed correct and the database
 * stayed correct -- only the *delivery* order was wrong, which is precisely the interleaving
 * the whole design is meant to rule out, and it appeared only across nodes and only under load.
 *
 * <p>Each user gets a chain of tasks rather than a shared worker: ordering is guaranteed within
 * a user and nothing is guaranteed between users, which is exactly the ordering the guarantee
 * actually claims. A striped thread pool would have been simpler, but one slow socket would
 * then stall every user sharing its stripe. Virtual threads make a chain per connected user
 * cost almost nothing, so there is no reason to accept that head-of-line blocking.
 */
@Component
class OrderedUserDelivery implements DisposableBean {

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();

    void inOrder(UUID userId, Runnable write) {
        // compute() is atomic per key, so two frames arriving for the same user cannot
        // interleave while linking themselves onto the chain.
        chains.compute(userId, (id, previous) -> {
            CompletableFuture<Void> after = previous == null
                    ? CompletableFuture.completedFuture(null)
                    // handle(), not thenRun(): a failed write must not poison every later
                    // frame for that user.
                    : previous.handle((ignored, error) -> null);
            return after.thenRunAsync(write, workers);
        });
    }

    /** Called when the last socket for a user closes, so the map does not grow forever. */
    void forget(UUID userId) {
        chains.remove(userId);
    }

    int trackedUsers() {
        return chains.size();
    }

    @Override
    public void destroy() {
        workers.shutdown();
    }
}
