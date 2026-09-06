package site.syamdev.shush.message;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import site.syamdev.shush.support.AbstractIT;
import site.syamdev.shush.support.TestUsers;
import site.syamdev.shush.support.WsClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The central claim, asserted in the build rather than only in the load harness: two
 * participants sending at the same time see one identical total order.
 *
 * <p>The harness in {@code bench/} runs the same invariants at scale and under node failure.
 * This exists so a regression fails {@code ./mvnw verify}, not just a benchmark someone
 * remembers to run.
 */
class MessageOrderingIT extends AbstractIT {

    private static final int MESSAGES_PER_PARTICIPANT = 40;
    private static final int TOTAL = MESSAGES_PER_PARTICIPANT * 2;

    @LocalServerPort
    private int port;

    @Test
    void concurrentSendersProduceOneOrderThatBothParticipantsAgreeOn() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt());
             WsClient bobWs = WsClient.connect(port, bob.jwt())) {

            CountDownLatch startTogether = new CountDownLatch(1);
            try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
                for (WsClient sender : List.of(aliceWs, bobWs)) {
                    senders.submit(() -> {
                        startTogether.await();
                        for (int i = 0; i < MESSAGES_PER_PARTICIPANT; i++) {
                            sender.sendText(conversationId, UUID.randomUUID(), "m" + i);
                        }
                        return null;
                    });
                }
                startTogether.countDown();
            }

            List<JsonNode> asAlice = aliceWs.awaitAll("message", TOTAL);
            List<JsonNode> asBob = bobWs.awaitAll("message", TOTAL);

            assertNoGaps(asAlice);
            assertNoDuplicateClientMsgIds(asAlice);
            assertSameOrder(asAlice, asBob);
            assertHistoryAgrees(alice, conversationId, asAlice);
        }
    }

    /** Every seq from 1..N exactly once: a gap means a lost write, a repeat means a double one. */
    private static void assertNoGaps(List<JsonNode> observed) {
        List<Long> seqs = observed.stream().map(f -> f.path("seq").asLong()).sorted().toList();
        assertThat(seqs).containsExactlyElementsOf(
                IntStream.rangeClosed(1, TOTAL).mapToObj(Long::valueOf).toList());
    }

    private static void assertNoDuplicateClientMsgIds(List<JsonNode> observed) {
        Set<String> ids = new HashSet<>();
        for (JsonNode frame : observed) {
            assertThat(ids.add(frame.path("clientMsgId").asText()))
                    .as("clientMsgId %s was delivered twice", frame.path("clientMsgId").asText())
                    .isTrue();
        }
    }

    /** Arrival order, not just contents: both sockets must have seen the same sequence of seqs. */
    private static void assertSameOrder(List<JsonNode> asAlice, List<JsonNode> asBob) {
        assertThat(asBob.stream().map(f -> f.path("seq").asLong()).toList())
                .containsExactlyElementsOf(asAlice.stream().map(f -> f.path("seq").asLong()).toList());
    }

    /** And the durable record agrees with what the sockets saw. */
    private void assertHistoryAgrees(TestUsers.Session caller, UUID conversationId,
                                     List<JsonNode> observed) {
        List<Long> persisted = new ArrayList<>();
        Long before = null;
        while (true) {
            JsonNode page = rest.exchange(
                    "/api/conversations/" + conversationId + "/messages?limit=100"
                            + (before == null ? "" : "&before=" + before),
                    org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(testUsers.authorised(caller)),
                    JsonNode.class).getBody();

            List<Long> seqs = new ArrayList<>();
            page.path("messages").forEach(m -> seqs.add(m.path("seq").asLong()));
            persisted.addAll(0, seqs);

            if (page.path("nextBefore").isNull() || page.path("nextBefore").isMissingNode()) {
                break;
            }
            before = page.path("nextBefore").asLong();
        }

        assertThat(persisted).hasSize(TOTAL);
        assertThat(persisted).containsExactlyElementsOf(
                observed.stream().map(f -> f.path("seq").asLong()).sorted().toList());
    }
}
