package site.syamdev.shush.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import site.syamdev.shush.support.AbstractPostgresIT;
import site.syamdev.shush.support.TestUsers;
import site.syamdev.shush.support.WsClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatIT extends AbstractPostgresIT {

    @LocalServerPort
    private int port;

    @Test
    void twoSessionsInOneConversationExchangeMessages() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt());
             WsClient bobWs = WsClient.connect(port, bob.jwt())) {

            UUID clientMsgId = UUID.randomUUID();
            aliceWs.sendText(conversationId, clientMsgId, "hello bob");

            JsonNode ack = aliceWs.await("ack");
            assertThat(ack.path("clientMsgId").asText()).isEqualTo(clientMsgId.toString());
            assertThat(ack.path("status").asText()).isEqualTo("sent");
            assertThat(ack.path("seq").asLong()).isEqualTo(1L);
            assertThat(ack.path("duplicate").asBoolean()).isFalse();

            JsonNode received = bobWs.await("message");
            assertThat(received.path("body").asText()).isEqualTo("hello bob");
            assertThat(received.path("senderId").asText()).isEqualTo(alice.userId().toString());
            assertThat(received.path("seq").asLong()).isEqualTo(1L);

            // The sender sees its own message on the same path everyone else does.
            JsonNode echoed = aliceWs.await("message");
            assertThat(echoed.path("messageId").asText()).isEqualTo(received.path("messageId").asText());

            bobWs.sendText(conversationId, UUID.randomUUID(), "hello alice");
            JsonNode reply = aliceWs.await("message");
            assertThat(reply.path("body").asText()).isEqualTo("hello alice");
            assertThat(reply.path("seq").asLong()).isEqualTo(2L);
        }
    }

    @Test
    void aRepeatedClientMsgIdIsAcceptedOnceAndFannedOutOnce() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt());
             WsClient bobWs = WsClient.connect(port, bob.jwt())) {

            UUID clientMsgId = UUID.randomUUID();
            aliceWs.sendText(conversationId, clientMsgId, "only once");
            JsonNode first = aliceWs.await("ack");

            // The same logical send, retried by a client that never saw the first ack.
            aliceWs.sendText(conversationId, clientMsgId, "only once");
            JsonNode second = aliceWs.await("ack");

            assertThat(second.path("duplicate").asBoolean()).isTrue();
            assertThat(second.path("messageId").asText()).isEqualTo(first.path("messageId").asText());
            assertThat(second.path("seq").asLong()).isEqualTo(first.path("seq").asLong());

            // Bob must never be shown the retry.
            aliceWs.sendText(conversationId, UUID.randomUUID(), "second message");
            List<JsonNode> seen = bobWs.awaitAll("message", 2);
            assertThat(seen.stream().map(f -> f.path("body").asText()))
                    .containsExactly("only once", "second message");
        }
    }

    @Test
    void aNonParticipantCannotSendToTheConversation() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        TestUsers.Session mallory = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient malloryWs = WsClient.connect(port, mallory.jwt())) {
            malloryWs.sendText(conversationId, UUID.randomUUID(), "let me in");

            JsonNode error = malloryWs.await("error");
            assertThat(error.path("code").asText()).isEqualTo("not_a_participant");
        }
    }

    @Test
    void aHandshakeWithoutAValidTokenIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> WsClient.connect(port, "not-a-jwt"))
                .isNotNull();
    }
}
