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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The path a client takes after its socket dies: ask for everything it missed, in order.
 * pre-plan.md 3 promises that anything sent while someone is away reaches them when they
 * return -- durability is worth nothing without a way to collect it.
 */
class ResumeIT extends AbstractIT {

    private static final int MESSAGES = 30;

    @LocalServerPort
    private int port;

    @Test
    void resumeReturnsExactlyWhatWasMissedInOrder() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt())) {
            for (int i = 1; i <= MESSAGES; i++) {
                aliceWs.sendText(conversationId, UUID.randomUUID(), "message " + i);
                assertThat(aliceWs.awaitAck("delivered").path("seq").asLong()).isEqualTo(i);
            }
        }

        // A client that holds up to seq 12 and was offline for the rest.
        assertThat(seqsAfter(bob, conversationId, 12))
                .containsExactlyElementsOf(LongStream.rangeClosed(13, MESSAGES).boxed().toList());

        // A client that missed nothing gets nothing back rather than an error.
        assertThat(seqsAfter(bob, conversationId, MESSAGES)).isEmpty();

        // A client that holds nothing gets the whole conversation, oldest first.
        assertThat(seqsAfter(bob, conversationId, 0))
                .containsExactlyElementsOf(LongStream.rangeClosed(1, MESSAGES).boxed().toList());
    }

    @Test
    void resumePagesForwardWithoutSkippingOrRepeating() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt())) {
            for (int i = 1; i <= MESSAGES; i++) {
                aliceWs.sendText(conversationId, UUID.randomUUID(), "message " + i);
                aliceWs.awaitAck("delivered");
            }
        }

        List<Long> walked = new ArrayList<>();
        long cursor = 0;
        for (int page = 0; page < 20; page++) {
            JsonNode body = fetch(bob, conversationId, "after=" + cursor + "&limit=7");
            body.path("messages").forEach(m -> walked.add(m.path("seq").asLong()));

            JsonNode next = body.path("nextAfter");
            if (next.isNull() || next.isMissingNode()) {
                break;
            }
            cursor = next.asLong();
        }

        assertThat(walked).containsExactlyElementsOf(
                LongStream.rangeClosed(1, MESSAGES).boxed().toList());
    }

    @Test
    void passingBothCursorsIsRejected() {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/conversations/" + conversationId + "/messages?before=5&after=2",
                HttpMethod.GET, new HttpEntity<>(testUsers.authorised(alice)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText()).isEqualTo("conflicting_cursors");
    }

    private List<Long> seqsAfter(TestUsers.Session caller, UUID conversationId, long after) {
        List<Long> seqs = new ArrayList<>();
        fetch(caller, conversationId, "after=" + after + "&limit=100")
                .path("messages").forEach(m -> seqs.add(m.path("seq").asLong()));
        return seqs;
    }

    private JsonNode fetch(TestUsers.Session caller, UUID conversationId, String query) {
        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/conversations/" + conversationId + "/messages?" + query,
                HttpMethod.GET, new HttpEntity<>(testUsers.authorised(caller)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
