package site.syamdev.shush.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import site.syamdev.shush.support.AbstractIT;
import site.syamdev.shush.support.TestUsers;
import site.syamdev.shush.support.WsClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatIT extends AbstractIT {

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

            // "sent" means the log accepted it. No sequence number exists yet.
            JsonNode sent = aliceWs.awaitAck("sent");
            assertThat(sent.path("clientMsgId").asText()).isEqualTo(clientMsgId.toString());
            assertThat(sent.path("seq").isNull()).isTrue();

            // "delivered" means the writer committed it and assigned its place in the order.
            JsonNode delivered = aliceWs.awaitAck("delivered");
            assertThat(delivered.path("seq").asLong()).isEqualTo(1L);
            assertThat(delivered.path("duplicate").asBoolean()).isFalse();

            JsonNode received = bobWs.await("message");
            assertThat(received.path("body").asText()).isEqualTo("hello bob");
            assertThat(received.path("senderId").asText()).isEqualTo(alice.userId().toString());
            assertThat(received.path("seq").asLong()).isEqualTo(1L);

            JsonNode echoed = aliceWs.await("message");
            assertThat(echoed.path("messageId").asText()).isEqualTo(received.path("messageId").asText());

            bobWs.sendText(conversationId, UUID.randomUUID(), "hello alice");
            JsonNode reply = aliceWs.await("message");
            assertThat(reply.path("body").asText()).isEqualTo("hello alice");
            assertThat(reply.path("seq").asLong()).isEqualTo(2L);
        }
    }

    @Test
    void aRepeatedClientMsgIdIsPersistedOnceAndDeliveredOnce() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt());
             WsClient bobWs = WsClient.connect(port, bob.jwt())) {

            UUID clientMsgId = UUID.randomUUID();
            aliceWs.sendText(conversationId, clientMsgId, "only once");
            JsonNode first = aliceWs.awaitAck("delivered");

            // The same logical send, retried by a client that never saw the first ack. Same key,
            // so it lands on the same partition behind the original -- the retry cannot overtake it.
            aliceWs.sendText(conversationId, clientMsgId, "only once");
            JsonNode second = aliceWs.await("ack",
                    frame -> frame.path("duplicate").asBoolean(), "ack with duplicate=true");

            assertThat(second.path("messageId").asText()).isEqualTo(first.path("messageId").asText());
            assertThat(second.path("seq").asLong()).isEqualTo(first.path("seq").asLong());

            // Bob must never be shown the retry. Sending a second, distinct message and
            // asserting on the pair proves the duplicate did not slip in between them.
            aliceWs.sendText(conversationId, UUID.randomUUID(), "second message");
            List<JsonNode> seen = bobWs.awaitAll("message", 2);
            assertThat(seen.stream().map(f -> f.path("body").asText()))
                    .containsExactly("only once", "second message");
            assertThat(seen.stream().map(f -> f.path("seq").asLong())).containsExactly(1L, 2L);
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
