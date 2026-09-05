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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryPaginationIT extends AbstractIT {

    private static final int MESSAGE_COUNT = 25;
    private static final int PAGE_SIZE = 10;

    @LocalServerPort
    private int port;

    @Test
    void historyPagesBackwardsAndStaysInSeqOrder() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt())) {
            for (int i = 1; i <= MESSAGE_COUNT; i++) {
                aliceWs.sendText(conversationId, UUID.randomUUID(), "message " + i);
                // Wait for the writer's ack, so the sequence order under test is the send order.
                assertThat(aliceWs.awaitAck("delivered").path("seq").asLong()).isEqualTo(i);
            }
        }

        List<Long> walked = new ArrayList<>();
        Long before = null;
        for (int page = 0; page < 10; page++) {
            JsonNode body = fetchPage(alice, conversationId, before, PAGE_SIZE);
            List<Long> seqs = new ArrayList<>();
            body.path("messages").forEach(m -> seqs.add(m.path("seq").asLong()));

            assertThat(seqs).isSorted();
            walked.addAll(0, seqs);

            if (body.path("nextBefore").isNull() || body.path("nextBefore").isMissingNode()) {
                break;
            }
            before = body.path("nextBefore").asLong();
        }

        assertThat(walked).containsExactlyElementsOf(
                IntStream.rangeClosed(1, MESSAGE_COUNT).mapToObj(Long::valueOf).toList());
    }

    @Test
    void aNonParticipantCannotReadHistory() {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        TestUsers.Session mallory = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/conversations/" + conversationId + "/messages",
                HttpMethod.GET, new HttpEntity<>(testUsers.authorised(mallory)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().path("code").asText()).isEqualTo("not_a_participant");
    }

    private JsonNode fetchPage(TestUsers.Session caller, UUID conversationId, Long before, int limit) {
        String url = "/api/conversations/" + conversationId + "/messages?limit=" + limit
                + (before == null ? "" : "&before=" + before);
        ResponseEntity<JsonNode> response = rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(testUsers.authorised(caller)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
