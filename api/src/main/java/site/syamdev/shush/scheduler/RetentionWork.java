package site.syamdev.shush.scheduler;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import site.syamdev.shush.media.MediaService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The actual SQL behind the sweeps, in plain statements rather than load-then-delete loops:
 * these run against whole tables and pulling the rows into memory first would be the slowest
 * possible way to delete them.
 */
@Component
class RetentionWork {

    private final EntityManager entityManager;
    private final MediaService media;
    private final Clock clock;
    private final Duration anonymousUserRetention;
    private final Duration unconfirmedMediaGrace;

    RetentionWork(EntityManager entityManager, MediaService media, Clock clock,
                  @Value("${shush.retention.anonymous-user}") Duration anonymousUserRetention,
                  @Value("${shush.retention.unconfirmed-media}") Duration unconfirmedMediaGrace) {
        this.entityManager = entityManager;
        this.media = media;
        this.clock = clock;
        this.anonymousUserRetention = anonymousUserRetention;
        this.unconfirmedMediaGrace = unconfirmedMediaGrace;
    }

    /** Messages and participants go with it: the foreign keys cascade. */
    @Transactional
    int purgeExpiredConversations() {
        return entityManager.createNativeQuery("""
                        delete from conversations
                        where purge_after is not null and purge_after <= :now
                        """)
                .setParameter("now", clock.instant())
                .executeUpdate();
    }

    /**
     * Seven days with no answer is an answer. The conversation then goes back on the clock,
     * because nobody ended up wanting it kept.
     */
    @Transactional
    int expireFriendRequests() {
        Instant now = clock.instant();
        int expired = entityManager.createNativeQuery("""
                        update friend_requests set status = 'expired'
                        where status = 'pending' and expires_at <= :now
                        """)
                .setParameter("now", now)
                .executeUpdate();

        if (expired > 0) {
            entityManager.createNativeQuery("""
                            update conversations c set purge_after = :purgeAfter
                            where c.purge_after is null
                              and c.state <> 'kept'
                              and not exists (
                                  select 1 from friend_requests r
                                  where r.conversation_id = c.id
                                    and r.status in ('pending', 'accepted'))
                            """)
                    .setParameter("purgeAfter", now)
                    .executeUpdate();
        }
        return expired;
    }

    /**
     * Two rules at once: anything past its expiry, and anything that was never confirmed. An
     * unconfirmed row means the client asked for an upload URL and then vanished, so the object
     * is either absent or orphaned -- either way nothing will ever reference it.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    int purgeExpiredMedia() {
        Instant now = clock.instant();
        List<String> keys = entityManager.createNativeQuery("""
                        select key from media_objects
                        where expires_at <= :now
                           or (status = 'pending' and created_at <= :abandoned)
                        """)
                .setParameter("now", now)
                .setParameter("abandoned", now.minus(unconfirmedMediaGrace))
                .getResultList();
        if (keys.isEmpty()) {
            return 0;
        }

        // The object goes first. Deleting the row first would lose the only record that the
        // object exists, leaving bytes in the bucket that nothing references and nothing can
        // find -- so a key whose object storage refuses to delete keeps its row for next time.
        List<String> deleted = keys.stream().filter(media::deleteObject).toList();
        if (deleted.isEmpty()) {
            return 0;
        }

        return entityManager.createNativeQuery("delete from media_objects where key in (:keys)")
                .setParameter("keys", deleted)
                .executeUpdate();
    }

    /**
     * Anonymous, no friendships, untouched for the retention window. An anonymous account with
     * friendships is never removed on the inactivity rule -- those people are somebody's
     * friends list, and deleting them would break the pair rather than tidy one side of it.
     */
    @Transactional
    int purgeAbandonedAnonymousUsers() {
        return entityManager.createNativeQuery("""
                        delete from users u
                        where u.is_anonymous
                          and u.last_seen_at <= :cutoff
                          and not exists (
                              select 1 from friendships f
                              where f.user_a_id = u.id or f.user_b_id = u.id)
                        """)
                .setParameter("cutoff", clock.instant().minus(anonymousUserRetention))
                .executeUpdate();
    }

    /**
     * Recomputes the counter from the messages the participant has not read. This is the one
     * place a {@code COUNT(*)} is correct: it runs nightly on one replica, not on every page
     * load, which is exactly the trade the maintained counter exists to make.
     */
    @Transactional
    int reconcileUnreadCounts() {
        return entityManager.createNativeQuery("""
                        update conversation_participants p
                        set unread_count = actual.count
                        from (
                            select cp.conversation_id, cp.user_id,
                                   count(m.id) filter (where m.sender_id <> cp.user_id) as count
                            from conversation_participants cp
                            left join messages m
                                   on m.conversation_id = cp.conversation_id
                                  and m.seq > cp.read_cursor_seq
                            group by cp.conversation_id, cp.user_id
                        ) as actual
                        where p.conversation_id = actual.conversation_id
                          and p.user_id = actual.user_id
                          and p.unread_count <> actual.count
                        """)
                .executeUpdate();
    }
}
