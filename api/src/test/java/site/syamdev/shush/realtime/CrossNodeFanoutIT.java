package site.syamdev.shush.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import site.syamdev.shush.ShushApplication;
import site.syamdev.shush.support.AbstractIT;
import site.syamdev.shush.support.TestUsers;
import site.syamdev.shush.support.WsClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case that only exists once there is more than one node, and the reason R4 makes three
 * replicas the default: user A holds a socket on one node, user B on another, and the first
 * node has no route to B's socket.
 *
 * <p>A second full application context is started against the same Postgres, Redis and broker.
 * That is heavier than mocking a backplane, and it is the only way to test the thing that
 * actually breaks -- a mocked publish would pass whether or not a real subscriber exists.
 */
class CrossNodeFanoutIT extends AbstractIT {

    private static ConfigurableApplicationContext secondNode;
    private static int secondNodePort;

    @LocalServerPort
    private int firstNodePort;

    @BeforeAll
    static void startSecondNode() {
        // Passed as command-line arguments, not via properties(): that sets *default*
        // properties, which application.yml then overrides -- so the node would have come up on
        // the configured port against the developer's local database rather than the container.
        secondNode = new SpringApplicationBuilder(ShushApplication.class).run(
                // The same profile the first node runs under. Without it this node reads the
                // developer's .env and tries to authenticate to containers that have no
                // authentication -- which shows up as cross-node delivery silently failing.
                "--spring.profiles.active=test",
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.kafka.bootstrap-servers=" + REDPANDA.getBootstrapServers(),
                // Easy to forget, and the failure is silent: without this the second node
                // talks to whatever is on the default port -- very possibly the developer's
                // own dev stack -- and the two nodes simply never see each other's users.
                "--spring.elasticsearch.uris=http://" + ELASTICSEARCH.getHttpHostAddress(),
                "--spring.docker.compose.enabled=false",
                // The same signing key, because a JWT issued by one node must be accepted by the
                // other. That is exactly what lets any node serve any user.
                "--shush.jwt.secret=test-secret-that-is-long-enough-for-hs256",
                "--shush.dev-endpoints.enabled=true",
                "--shush.node-id=second-node");
        secondNodePort = Integer.parseInt(
                secondNode.getEnvironment().getProperty("local.server.port", "0"));
    }

    @AfterAll
    static void stopSecondNode() {
        if (secondNode != null) {
            secondNode.close();
        }
    }

    @Test
    void aMessageReachesAParticipantConnectedToADifferentNode() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient onFirstNode = WsClient.connect(firstNodePort, alice.jwt());
             WsClient onSecondNode = WsClient.connect(secondNodePort, bob.jwt())) {

            String firstNodeId = onFirstNode.await("hello").path("nodeId").asText();
            String secondNodeId = onSecondNode.await("hello").path("nodeId").asText();

            // Without this the test could silently be single-node and prove nothing.
            assertThat(firstNodeId)
                    .as("the two sockets must be served by different nodes")
                    .isNotEqualTo(secondNodeId);

            UUID clientMsgId = UUID.randomUUID();
            onFirstNode.sendText(conversationId, clientMsgId, "across the backplane");

            JsonNode received = onSecondNode.await("message");
            assertThat(received.path("body").asText()).isEqualTo("across the backplane");
            assertThat(received.path("senderId").asText()).isEqualTo(alice.userId().toString());
            assertThat(received.path("seq").asLong()).isEqualTo(1L);

            // And the sender's ack came back, even though the writer that sequenced the message
            // may have been the other node entirely.
            JsonNode delivered = onFirstNode.awaitAck("delivered");
            assertThat(delivered.path("clientMsgId").asText()).isEqualTo(clientMsgId.toString());
            assertThat(delivered.path("seq").asLong()).isEqualTo(1L);
        }
    }

    @Test
    void bothDirectionsWorkAcrossNodes() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient onFirstNode = WsClient.connect(firstNodePort, alice.jwt());
             WsClient onSecondNode = WsClient.connect(secondNodePort, bob.jwt())) {

            onSecondNode.sendText(conversationId, UUID.randomUUID(), "from the second node");
            assertThat(onFirstNode.await("message").path("body").asText())
                    .isEqualTo("from the second node");
            // The sender sees its own message too, on the same path as everyone else -- there is
            // no local shortcut, so this frame also came back across the backplane.
            assertThat(onSecondNode.await("message").path("body").asText())
                    .isEqualTo("from the second node");

            onFirstNode.sendText(conversationId, UUID.randomUUID(), "and back again");
            assertThat(onSecondNode.await("message").path("body").asText())
                    .isEqualTo("and back again");
            assertThat(onFirstNode.await("message").path("body").asText())
                    .isEqualTo("and back again");
        }
    }

    /**
     * Regression test. The backplane container dispatched each received message on its own
     * thread, so frames for one conversation raced each other to the socket: sequence numbers
     * and the database stayed correct while the two participants saw different orders. It
     * showed up only across nodes and only in bursts, which is why a single-message test misses
     * it entirely.
     */
    @Test
    void aBurstAcrossNodesArrivesInOneOrderOnBothSockets() throws Exception {
        int perParticipant = 40;
        int total = perParticipant * 2;

        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient onFirstNode = WsClient.connect(firstNodePort, alice.jwt());
             WsClient onSecondNode = WsClient.connect(secondNodePort, bob.jwt())) {

            assertThat(onFirstNode.await("hello").path("nodeId").asText())
                    .isNotEqualTo(onSecondNode.await("hello").path("nodeId").asText());

            CountDownLatch startTogether = new CountDownLatch(1);
            try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
                for (WsClient sender : List.of(onFirstNode, onSecondNode)) {
                    senders.submit(() -> {
                        startTogether.await();
                        for (int i = 0; i < perParticipant; i++) {
                            sender.sendText(conversationId, UUID.randomUUID(), "burst" + i);
                        }
                        return null;
                    });
                }
                startTogether.countDown();
            }

            List<Long> asAlice = onFirstNode.awaitAll("message", total).stream()
                    .map(frame -> frame.path("seq").asLong()).toList();
            List<Long> asBob = onSecondNode.awaitAll("message", total).stream()
                    .map(frame -> frame.path("seq").asLong()).toList();

            // Arrival order, not just contents. Two sockets holding the same messages in
            // different orders is exactly the failure this asserts against.
            assertThat(asAlice).containsExactlyElementsOf(asBob);
            assertThat(asAlice).isSorted();
            assertThat(asAlice).containsExactlyElementsOf(
                    IntStream.rangeClosed(1, total).mapToObj(Long::valueOf).toList());
        }
    }

    /**
     * Matching spans nodes for the same reason delivery does: the wait pool and the atomic
     * claim live in Redis, not in either process, so two people looking for someone from
     * different replicas find each other and both get told.
     */
    @Test
    void twoPeopleOnDifferentNodesAreMatchedWithEachOther() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();

        try (WsClient onFirstNode = WsClient.connect(firstNodePort, alice.jwt());
             WsClient onSecondNode = WsClient.connect(secondNodePort, bob.jwt())) {

            assertThat(onFirstNode.await("hello").path("nodeId").asText())
                    .isNotEqualTo(onSecondNode.await("hello").path("nodeId").asText());

            onFirstNode.send("{\"type\":\"find\",\"interestIds\":[1,2],\"patience\":0}");
            onSecondNode.send("{\"type\":\"find\",\"interestIds\":[2,3],\"patience\":0}");

            JsonNode asAlice = onFirstNode.await("matched");
            JsonNode asBob = onSecondNode.await("matched");

            assertThat(asAlice.path("conversationId").asText())
                    .as("one conversation, not one each")
                    .isEqualTo(asBob.path("conversationId").asText());
            assertThat(asAlice.path("withUserId").asText()).isEqualTo(bob.userId().toString());
            assertThat(asBob.path("withUserId").asText()).isEqualTo(alice.userId().toString());
            assertThat(asAlice.path("randomMatch").asBoolean()).isFalse();

            // And the conversation they were given actually works across the two nodes.
            UUID conversationId = UUID.fromString(asAlice.path("conversationId").asText());
            onFirstNode.sendText(conversationId, UUID.randomUUID(), "hello from the match");
            assertThat(onSecondNode.await("message").path("body").asText())
                    .isEqualTo("hello from the match");
        }
    }
}
