package site.syamdev.shush.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import site.syamdev.shush.support.AbstractIT;
import site.syamdev.shush.support.TestUsers;
import site.syamdev.shush.support.WsClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UnreadAndReceiptsIT extends AbstractIT {

    @LocalServerPort
    private int port;

    @Test
    void unreadCountsRiseForTheRecipientOnlyAndResetOnRead() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt());
             WsClient bobWs = WsClient.connect(port, bob.jwt())) {

            for (int i = 1; i <= 3; i++) {
                aliceWs.sendText(conversationId, UUID.randomUUID(), "message " + i);
                aliceWs.awaitAck("delivered");
            }

            // Maintained by the writer, never counted at read time.
            assertThat(unreadFor(bob, conversationId)).isEqualTo(3);
            assertThat(unreadFor(alice, conversationId))
                    .as("your own messages are not unread for you")
                    .isZero();

            bobWs.send("{\"type\":\"read\",\"conversationId\":\"" + conversationId + "\",\"seq\":3}");

            JsonNode receipt = aliceWs.await("read");
            assertThat(receipt.path("userId").asText()).isEqualTo(bob.userId().toString());
            assertThat(receipt.path("seq").asLong()).isEqualTo(3L);

            assertThat(unreadFor(bob, conversationId)).isZero();
            assertThat(readCursorOfOther(alice, conversationId)).isEqualTo(3L);
        }
    }

    /** Two devices reading at once must not let the slower one resurrect read messages. */
    @Test
    void theReadCursorNeverMovesBackwards() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt());
             WsClient bobWs = WsClient.connect(port, bob.jwt())) {

            for (int i = 1; i <= 4; i++) {
                aliceWs.sendText(conversationId, UUID.randomUUID(), "message " + i);
                aliceWs.awaitAck("delivered");
            }

            bobWs.send("{\"type\":\"read\",\"conversationId\":\"" + conversationId + "\",\"seq\":4}");
            aliceWs.await("read");

            // A stale read from a slower device. It must not move the cursor, and it must not
            // fire a second receipt.
            bobWs.send("{\"type\":\"read\",\"conversationId\":\"" + conversationId + "\",\"seq\":2}");

            aliceWs.sendText(conversationId, UUID.randomUUID(), "after the stale read");
            aliceWs.awaitAck("delivered");

            assertThat(readCursorOfOther(alice, conversationId)).isEqualTo(4L);
        }
    }

    @Test
    void leavingEndsTheConversationAndTellsTheOtherPerson() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt());
             WsClient bobWs = WsClient.connect(port, bob.jwt())) {
            aliceWs.await("hello");
            bobWs.await("hello");

            aliceWs.send("{\"type\":\"leave\",\"conversationId\":\"" + conversationId + "\"}");

            // "Left", not "offline". The distinction is visible to the other person on purpose.
            JsonNode left = bobWs.await("left");
            assertThat(left.path("userId").asText()).isEqualTo(alice.userId().toString());
            assertThat(left.path("conversationId").asText()).isEqualTo(conversationId.toString());

            assertThat(conversationOf(bob, conversationId).path("state").asText()).isEqualTo("ended");
        }
    }

    private int unreadFor(TestUsers.Session caller, UUID conversationId) {
        return conversationOf(caller, conversationId).path("unreadCount").asInt();
    }

    private long readCursorOfOther(TestUsers.Session caller, UUID conversationId) {
        return conversationOf(caller, conversationId).path("others").get(0).path("readCursorSeq").asLong();
    }

    private JsonNode conversationOf(TestUsers.Session caller, UUID conversationId) {
        ResponseEntity<JsonNode> response = rest.exchange("/api/conversations/" + conversationId,
                HttpMethod.GET, new HttpEntity<>(testUsers.authorised(caller)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
