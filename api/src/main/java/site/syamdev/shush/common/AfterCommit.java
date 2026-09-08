package site.syamdev.shush.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers work until the surrounding transaction has actually committed.
 *
 * <p>Telling somebody about a row before that row is visible is a race, and it is one that
 * hides well: the notification is published, delivered, and handled correctly, but the reader
 * it wakes up queries a transaction that has not committed yet and sees nothing. Nothing fails,
 * nothing is logged, and the update simply never appears — until some unrelated event happens
 * to trigger another read. It looks exactly like a delivery bug, and it is not one.
 *
 * <p>This mattered for friend requests, where the recipient's client reacts to the frame by
 * re-reading {@code /api/friend-requests}. That read routinely beat the commit, so the request
 * existed in the database and was invisible on screen with no error anywhere.
 *
 * <p>Outside a transaction the action simply runs now, so a caller does not have to know
 * whether it happens to be inside one.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
