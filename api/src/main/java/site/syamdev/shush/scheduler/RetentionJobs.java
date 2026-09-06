package site.syamdev.shush.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The five sweeps from plan.md 3.10, each behind {@link JobLock} so it runs on one replica.
 *
 * <p>These enforce the retention rules the product actually promises: a stranger conversation
 * nobody asked to keep is deleted, an unanswered friend request stops being pending, images in
 * anonymous conversations do not live forever, and an abandoned anonymous account with nothing
 * attached to it eventually goes. Retention that is only written down is not retention.
 */
@Component
class RetentionJobs {

    private static final Logger log = LoggerFactory.getLogger(RetentionJobs.class);

    private final JobLock lock;
    private final RetentionWork work;
    private final Duration lease;

    RetentionJobs(JobLock lock, RetentionWork work,
                  @Value("${shush.scheduler.lease}") Duration lease) {
        this.lock = lock;
        this.work = work;
        this.lease = lease;
    }

    @Scheduled(fixedRateString = "${shush.scheduler.purge-conversations}")
    void purgeConversations() {
        lock.runIfNotHeld("purge-conversations", lease, () -> {
            int purged = work.purgeExpiredConversations();
            if (purged > 0) {
                log.info("purged {} conversation(s) nobody asked to keep", purged);
            }
        });
    }

    @Scheduled(fixedRateString = "${shush.scheduler.expire-friend-requests}")
    void expireFriendRequests() {
        lock.runIfNotHeld("expire-friend-requests", lease, () -> {
            int expired = work.expireFriendRequests();
            if (expired > 0) {
                log.info("expired {} unanswered friend request(s)", expired);
            }
        });
    }

    @Scheduled(fixedRateString = "${shush.scheduler.purge-media}")
    void purgeMedia() {
        lock.runIfNotHeld("purge-media", lease, () -> {
            int purged = work.purgeExpiredMedia();
            if (purged > 0) {
                log.info("purged {} media object(s)", purged);
            }
        });
    }

    @Scheduled(fixedRateString = "${shush.scheduler.purge-anonymous-users}")
    void purgeAnonymousUsers() {
        lock.runIfNotHeld("purge-anonymous-users", lease, () -> {
            int purged = work.purgeAbandonedAnonymousUsers();
            if (purged > 0) {
                log.info("purged {} abandoned anonymous account(s)", purged);
            }
        });
    }

    /**
     * The counter is maintained rather than derived, which is the whole point of it -- but a
     * maintained counter drifts, so something has to put it back. Documented as a deliberate
     * eventual-consistency trade rather than pretended away.
     */
    @Scheduled(fixedRateString = "${shush.scheduler.reconcile-unread}")
    void reconcileUnread() {
        lock.runIfNotHeld("reconcile-unread", lease, () -> {
            int corrected = work.reconcileUnreadCounts();
            if (corrected > 0) {
                log.info("corrected {} drifted unread count(s)", corrected);
            }
        });
    }
}
