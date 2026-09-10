package site.syamdev.shush.message;

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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Replying, reacting, and the two different things people mean by "delete". */
class MessageInteractionIT extends AbstractIT {

    @LocalServerPort
    private int port;

    @Test
    void aReplyCarriesTheSeqItAnswers() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt());
             WsClient bobWs = WsClient.connect(port, bob.jwt())) {
            aliceWs.await("hello");
            bobWs.await("hello");

            aliceWs.sendText(conversationId, UUID.randomUUID(), "the original");
            long original = aliceWs.awaitAck("delivered").path("seq").asLong();

            bobWs.sendReply(conversationId, UUID.randomUUID(), "answering that", original);
            bobWs.awaitAck("delivered");

            JsonNode delivered = aliceWs.await("message",
                    frame -> "answering that".equals(frame.path("body").asText()), "the reply");
            assertThat(delivered.path("replyToSeq").asLong())
                    .as("the quote survives the round trip through the log")
                    .isEqualTo(original);
        }

        assertThat(history(conversationId, alice).path("messages").get(1).path("replyToSeq").asLong())
                .as("and it is durable, not just in the frame")
                .isEqualTo(1L);
    }

    @Test
    void reactingReplacesRatherThanAccumulates() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt());
             WsClient bobWs = WsClient.connect(port, bob.jwt())) {
            aliceWs.await("hello");
            bobWs.await("hello");
            aliceWs.sendText(conversationId, UUID.randomUUID(), "react to this");
            String messageId = aliceWs.awaitAck("delivered").path("messageId").asText();

            assertThat(react(messageId, bob, "❤️").getStatusCode()).isEqualTo(HttpStatus.OK);
            // The sender hears about it, which is the whole point of reacting.
            assertThat(aliceWs.await("reaction").path("emoji").asText()).isEqualTo("❤️");

            assertThat(react(messageId, bob, "😂").getStatusCode()).isEqualTo(HttpStatus.OK);
            aliceWs.await("reaction");

            JsonNode reactions = history(conversationId, alice)
                    .path("messages").get(0).path("reactions");
            assertThat(reactions).hasSize(1);
            assertThat(reactions.get(0).path("emoji").asText())
                    .as("one reaction per person, replaced not added")
                    .isEqualTo("😂");

            assertThat(rest.exchange("/api/messages/" + messageId + "/reaction", HttpMethod.DELETE,
                    new HttpEntity<>(testUsers.authorised(bob)), Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(history(conversationId, alice).path("messages").get(0).path("reactions"))
                    .isEmpty();
        }
    }

    @Test
    void anythingButAnAllowedReactionIsRefused() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt())) {
            aliceWs.await("hello");
            aliceWs.sendText(conversationId, UUID.randomUUID(), "react to this");
            String messageId = aliceWs.awaitAck("delivered").path("messageId").asText();

            assertThat(react(messageId, bob, "not an emoji at all").getStatusCode())
                    .as("the reaction is rendered by every client, so it is an allowlist")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void deletingForEveryoneRemovesTheWordsAndTellsBothSides() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt());
             WsClient bobWs = WsClient.connect(port, bob.jwt())) {
            aliceWs.await("hello");
            bobWs.await("hello");
            aliceWs.sendText(conversationId, UUID.randomUUID(), "something regrettable");
            String messageId = aliceWs.awaitAck("delivered").path("messageId").asText();
            bobWs.await("message");

            assertThat(remove(messageId, bob).getStatusCode())
                    .as("only the sender may take words back")
                    .isEqualTo(HttpStatus.FORBIDDEN);

            assertThat(remove(messageId, alice).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bobWs.await("deleted").path("seq").asLong()).isEqualTo(1L);
        }

        JsonNode message = history(conversationId, bob).path("messages").get(0);
        assertThat(message.path("deleted").asBoolean()).isTrue();
        assertThat(message.path("body").isNull())
                .as("deleted means the text is gone, not that a client agrees to hide it")
                .isTrue();
        assertThat(message.path("seq").asLong())
                .as("the seq stays: a gap would read as a lost message to the harness")
                .isEqualTo(1L);
    }

    @Test
    void hidingAMessageAffectsOnlyThePersonWhoHidIt() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt())) {
            aliceWs.await("hello");
            aliceWs.sendText(conversationId, UUID.randomUUID(), "not for bob's eyes");
            String messageId = aliceWs.awaitAck("delivered").path("messageId").asText();

            assertThat(rest.exchange("/api/messages/" + messageId + "/hide", HttpMethod.POST,
                    new HttpEntity<>(testUsers.authorised(bob)), Void.class).getStatusCode())
                    .as("you may hide anyone's message from yourself")
                    .isEqualTo(HttpStatus.OK);
        }

        assertThat(history(conversationId, bob).path("messages")).isEmpty();
        assertThat(history(conversationId, alice).path("messages"))
                .as("and it says nothing to the other person")
                .hasSize(1);
    }

    @Test
    void historyListsStrangerConversationsToo() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt())) {
            aliceWs.await("hello");
            aliceWs.sendText(conversationId, UUID.randomUUID(), "hello stranger");
            aliceWs.awaitAck("delivered");
        }

        JsonNode conversations = rest.exchange("/api/conversations", HttpMethod.GET,
                new HttpEntity<>(testUsers.authorised(bob)), JsonNode.class).getBody();

        assertThat(conversations).hasSize(1);
        JsonNode summary = conversations.get(0);
        assertThat(summary.path("id").asText()).isEqualTo(conversationId.toString());
        assertThat(summary.path("kind").asText())
                .as("nobody kept anybody, and it is still history")
                .isEqualTo("stranger");
        assertThat(summary.path("peerId").asText()).isEqualTo(alice.userId().toString());
        assertThat(summary.path("lastMessage").asText()).isEqualTo("hello stranger");
        assertThat(summary.path("lastFromMe").asBoolean()).isFalse();
        assertThat(summary.path("unreadCount").asInt()).isEqualTo(1);
    }

    private ResponseEntity<Void> react(String messageId, TestUsers.Session caller, String emoji) {
        return rest.exchange("/api/messages/" + messageId + "/reaction", HttpMethod.PUT,
                new HttpEntity<>(Map.of("emoji", emoji), testUsers.authorised(caller)), Void.class);
    }

    private ResponseEntity<Void> remove(String messageId, TestUsers.Session caller) {
        return rest.exchange("/api/messages/" + messageId, HttpMethod.DELETE,
                new HttpEntity<>(testUsers.authorised(caller)), Void.class);
    }

    private JsonNode history(UUID conversationId, TestUsers.Session caller) {
        return rest.exchange("/api/conversations/" + conversationId + "/messages", HttpMethod.GET,
                new HttpEntity<>(testUsers.authorised(caller)), JsonNode.class).getBody();
    }
}
