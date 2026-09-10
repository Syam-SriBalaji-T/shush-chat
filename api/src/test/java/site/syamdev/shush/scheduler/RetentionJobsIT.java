package site.syamdev.shush.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import site.syamdev.shush.conversation.Conversation;
import site.syamdev.shush.conversation.ConversationRepository;
import site.syamdev.shush.support.AbstractIT;
import site.syamdev.shush.support.TestUsers;
import site.syamdev.shush.support.WsClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionJobsIT extends AbstractIT {

    @LocalServerPort
    private int port;

    @Autowired
    private RetentionJobs jobs;

    @Autowired
    private JobLock lock;

    @Autowired
    private ConversationRepository conversations;

    @Autowired
    private site.syamdev.shush.media.MediaObjectRepository mediaObjects;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    /**
     * An empty conversation is reaped -- a matched pair who never spoke, or one that ended
     * before a word was sent. There is no history in it to keep.
     */
    @Test
    void aConversationNobodySaidAnythingInIsPurged() {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        // Backdate the purge deadline rather than waiting an hour for it.
        setPurgeAfterToThePast(conversationId);
        assertThat(conversations.findById(conversationId)).isPresent();

        jobs.purgeConversations();

        assertThat(conversations.findById(conversationId))
                .as("nothing was said, so there is nothing to keep")
                .isEmpty();
    }

    /**
     * A stranger conversation with something in it survives, because it is history.
     *
     * <p>This reverses pre-plan.md, which had every unkept stranger conversation deleted an
     * hour after it ended. The owner asked for history of everything, strangers included, and
     * the change is recorded under Open Choices. The storage cost pre-plan.md was protecting is
     * images, and those still expire on their own schedule -- see the media test below.
     */
    @Test
    void aStrangerConversationWithMessagesIsKeptAsHistory() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt())) {
            aliceWs.sendText(conversationId, UUID.randomUUID(), "nobody will keep this");
            aliceWs.awaitAck("delivered");
        }

        setPurgeAfterToThePast(conversationId);
        jobs.purgeConversations();

        assertThat(conversations.findById(conversationId))
                .as("something was said, so it is history rather than rubbish")
                .isPresent();
        assertThat(messageCountFor(conversationId)).isEqualTo(1);
    }

    @Test
    void aKeptConversationSurvivesThePurge() {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        String requestId = rest.exchange(
                        "/api/conversations/" + conversationId + "/friend-request", HttpMethod.POST,
                        new HttpEntity<>(testUsers.authorised(alice)), com.fasterxml.jackson.databind.JsonNode.class)
                .getBody().path("id").asText();
        assertThat(rest.exchange("/api/friend-requests/" + requestId + "/accept", HttpMethod.POST,
                new HttpEntity<>(testUsers.authorised(bob)), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        jobs.purgeConversations();

        assertThat(conversations.findById(conversationId)).isPresent();
        assertThat(conversations.findById(conversationId).orElseThrow().getState())
                .isEqualTo(Conversation.State.KEPT);
    }

    @Test
    void anUnansweredRequestExpiresAndPutsTheConversationBackOnTheClock() {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        rest.exchange("/api/conversations/" + conversationId + "/friend-request", HttpMethod.POST,
                new HttpEntity<>(testUsers.authorised(alice)), Void.class);
        assertThat(conversations.findById(conversationId).orElseThrow().getPurgeAfter()).isNull();

        backdateFriendRequests(conversationId);
        jobs.expireFriendRequests();

        assertThat(conversations.findById(conversationId).orElseThrow().getPurgeAfter())
                .as("seven days with no answer is an answer")
                .isNotNull();
    }

    /**
     * The counter is maintained rather than derived, which is the point -- and a maintained
     * counter drifts, so something has to put it back.
     */
    @Test
    void reconciliationCorrectsADriftedUnreadCount() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt())) {
            for (int i = 0; i < 3; i++) {
                aliceWs.sendText(conversationId, UUID.randomUUID(), "message " + i);
                aliceWs.awaitAck("delivered");
            }
        }

        corruptUnreadCount(conversationId, bob.userId(), 99);
        jobs.reconcileUnread();

        assertThat(unreadCount(conversationId, bob.userId())).isEqualTo(3);
        assertThat(unreadCount(conversationId, alice.userId()))
                .as("your own messages are never unread for you")
                .isZero();
    }

    /**
     * A client that asked for an upload URL and then vanished leaves a pending row and, possibly,
     * an orphaned object. Neither will ever be referenced by anything, so both have to go.
     */
    @Test
    void anAbandonedUploadIsPurged() {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        String key = rest.exchange("/api/media/upload-url", HttpMethod.POST,
                        new HttpEntity<>(java.util.Map.of(
                                "conversationId", conversationId.toString(),
                                "mime", "image/png",
                                "sizeBytes", 1024), testUsers.authorised(alice)),
                        com.fasterxml.jackson.databind.JsonNode.class)
                .getBody().path("key").asText();
        assertThat(mediaObjects.findById(key)).isPresent();

        backdateMediaCreation(key);
        jobs.purgeMedia();

        assertThat(mediaObjects.findById(key)).isEmpty();
    }

    /**
     * Every replica runs the same timers, so without the lock a purge would run three times at
     * once -- three transactions competing to delete the same rows.
     */
    @Test
    void aJobNeverRunsConcurrentlyWithItself() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        AtomicInteger ran = new AtomicInteger();
        CountDownLatch startTogether = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                attempts.add(pool.submit(() -> {
                    startTogether.await();
                    return lock.runIfNotHeld("test-job", Duration.ofSeconds(30), () -> {
                        maxObserved.accumulateAndGet(running.incrementAndGet(), Math::max);
                        ran.incrementAndGet();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        running.decrementAndGet();
                    });
                }));
            }
            startTogether.countDown();
            for (Future<Boolean> attempt : attempts) {
                attempt.get();
            }
        }

        assertThat(maxObserved.get())
                .as("the job may never overlap itself")
                .isEqualTo(1);
        assertThat(ran.get())
                .as("contending ticks are skipped, never queued up to run later")
                .isLessThan(16);
    }

    private void backdateMediaCreation(String key) {
        inTransaction(em -> em.createNativeQuery(
                        "update media_objects set created_at = now() - interval '2 hours' "
                                + "where key = :key")
                .setParameter("key", key)
                .executeUpdate());
    }

    private void setPurgeAfterToThePast(UUID conversationId) {
        inTransaction(em -> em.createNativeQuery(
                        "update conversations set purge_after = now() - interval '1 hour' where id = :id")
                .setParameter("id", conversationId)
                .executeUpdate());
    }

    private void backdateFriendRequests(UUID conversationId) {
        inTransaction(em -> em.createNativeQuery(
                        "update friend_requests set expires_at = now() - interval '1 day' "
                                + "where conversation_id = :id")
                .setParameter("id", conversationId)
                .executeUpdate());
    }

    private void corruptUnreadCount(UUID conversationId, UUID userId, int value) {
        inTransaction(em -> em.createNativeQuery(
                        "update conversation_participants set unread_count = :value "
                                + "where conversation_id = :conversationId and user_id = :userId")
                .setParameter("value", value)
                .setParameter("conversationId", conversationId)
                .setParameter("userId", userId)
                .executeUpdate());
    }

    private int unreadCount(UUID conversationId, UUID userId) {
        return query(em -> ((Number) em.createNativeQuery(
                        "select unread_count from conversation_participants "
                                + "where conversation_id = :conversationId and user_id = :userId")
                .setParameter("conversationId", conversationId)
                .setParameter("userId", userId)
                .getSingleResult()).intValue());
    }

    private long messageCountFor(UUID conversationId) {
        return query(em -> ((Number) em.createNativeQuery(
                        "select count(*) from messages where conversation_id = :id")
                .setParameter("id", conversationId)
                .getSingleResult()).longValue());
    }

    private void inTransaction(java.util.function.Consumer<jakarta.persistence.EntityManager> work) {
        try (jakarta.persistence.EntityManager em = entityManagerFactory.createEntityManager()) {
            em.getTransaction().begin();
            work.accept(em);
            em.getTransaction().commit();
        }
    }

    private <T> T query(java.util.function.Function<jakarta.persistence.EntityManager, T> work) {
        try (jakarta.persistence.EntityManager em = entityManagerFactory.createEntityManager()) {
            return work.apply(em);
        }
    }
}
