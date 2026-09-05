package site.syamdev.shush.presence;

import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import site.syamdev.shush.support.AbstractIT;
import site.syamdev.shush.support.TestUsers;
import site.syamdev.shush.support.WsClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TypingIT extends AbstractIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TypingService typing;

    @Test
    void theTypingIndicatorExpiresOnItsOwn() {
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        assertThat(typing.accept(conversationId, userId)).isTrue();
        assertThat(typing.isTyping(conversationId, userId)).isTrue();

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(typing.isTyping(conversationId, userId)).isFalse());
    }

    /**
     * The server enforces the throttle rather than trusting the client to. A keystroke-rate
     * event that reaches the datastore unthrottled is the classic way this feature takes a
     * chat service down, and a client with a bug is not a hypothetical.
     */
    @Test
    void aSecondTypingEventInsideTheWindowIsRefused() {
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        assertThat(typing.accept(conversationId, userId)).isTrue();
        assertThat(typing.accept(conversationId, userId)).isFalse();
        assertThat(typing.accept(conversationId, userId)).isFalse();
    }

    @Test
    void typingReachesTheOtherPersonButNotTheSender() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt());
             WsClient bobWs = WsClient.connect(port, bob.jwt())) {
            aliceWs.await("hello");
            bobWs.await("hello");

            aliceWs.send("{\"type\":\"typing\",\"conversationId\":\"" + conversationId + "\"}");

            JsonNode seen = bobWs.await("typing");
            assertThat(seen.path("userId").asText()).isEqualTo(alice.userId().toString());
            assertThat(seen.path("conversationId").asText()).isEqualTo(conversationId.toString());

            // Sending a message means you have stopped typing it.
            aliceWs.sendText(conversationId, UUID.randomUUID(), "done typing");
            aliceWs.awaitAck("delivered");
            assertThat(typing.isTyping(conversationId, alice.userId())).isFalse();
        }
    }
}
