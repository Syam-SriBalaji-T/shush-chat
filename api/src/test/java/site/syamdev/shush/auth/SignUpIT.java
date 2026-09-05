package site.syamdev.shush.auth;

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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing up attaches an email to the account you already have. Nothing is created and nothing
 * is copied, which is the entire reason nothing resets (pre-plan.md 8.1) -- so the test that
 * matters is that the id, the name and the history are all still the same afterwards.
 */
class SignUpIT extends AbstractIT {

    @LocalServerPort
    private int port;

    @Test
    void signingUpKeepsTheSameUserNameAndMessages() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt())) {
            aliceWs.sendText(conversationId, UUID.randomUUID(), "before signing up");
            aliceWs.awaitAck("delivered");
        }

        String email = "alice-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<JsonNode> signedUp = post("/api/auth/signup",
                Map.of("email", email, "password", "a-long-enough-password"), alice);

        assertThat(signedUp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signedUp.getBody().path("user").path("id").asText())
                .as("the same account, now with an email on it")
                .isEqualTo(alice.userId().toString());
        assertThat(signedUp.getBody().path("user").path("displayName").asText())
                .isEqualTo(alice.displayName());
        assertThat(signedUp.getBody().path("user").path("anonymous").asBoolean()).isFalse();

        // Signing in from another device reaches the same account and the same history.
        JsonNode signedIn = rest.postForObject("/api/auth/login",
                Map.of("email", email, "password", "a-long-enough-password"), JsonNode.class);
        assertThat(signedIn.path("user").path("id").asText()).isEqualTo(alice.userId().toString());

        TestUsers.Session onNewDevice = new TestUsers.Session(alice.userId(), alice.displayName(),
                null, signedIn.path("jwt").asText());
        assertThat(bodiesOfHistory(onNewDevice, conversationId)).containsExactly("before signing up");
    }

    @Test
    void theSameEmailCannotBeUsedTwice() {
        TestUsers.Session first = testUsers.newAnonymous();
        TestUsers.Session second = testUsers.newAnonymous();
        String email = "taken-" + UUID.randomUUID() + "@example.com";

        assertThat(post("/api/auth/signup", Map.of("email", email, "password", "a-long-enough-password"), first)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> clash = post("/api/auth/signup",
                Map.of("email", email, "password", "another-long-password"), second);
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(clash.getBody().path("code").asText()).isEqualTo("email_taken");
    }

    @Test
    void theWrongPasswordIsRejected() {
        TestUsers.Session alice = testUsers.newAnonymous();
        String email = "wrong-" + UUID.randomUUID() + "@example.com";
        post("/api/auth/signup", Map.of("email", email, "password", "a-long-enough-password"), alice);

        ResponseEntity<JsonNode> response = rest.postForEntity("/api/auth/login",
                Map.of("email", email, "password", "not-the-password"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().path("code").asText()).isEqualTo("invalid_credentials");
    }

    @Test
    void anUnknownEmailIsRejectedTheSameWayAsAWrongPassword() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/auth/login",
                Map.of("email", "nobody-" + UUID.randomUUID() + "@example.com", "password", "whatever"),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().path("code").asText()).isEqualTo("invalid_credentials");
    }

    @Test
    void signingOutForgetsTheDeviceButNotTheAccount() {
        TestUsers.Session alice = testUsers.newAnonymous();

        ResponseEntity<Void> signedOut = rest.exchange("/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(Map.of("token", alice.deviceToken()), testUsers.authorised(alice)),
                Void.class);
        assertThat(signedOut.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The browser's key is gone; the account behind it is untouched but now unreachable
        // from here, which is exactly the loss pre-plan.md 5 warns about.
        ResponseEntity<JsonNode> resumed = rest.postForEntity("/api/auth/device",
                Map.of("token", alice.deviceToken()), JsonNode.class);
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<JsonNode> post(String path, Map<String, ?> body, TestUsers.Session caller) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, testUsers.authorised(caller)), JsonNode.class);
    }

    private List<String> bodiesOfHistory(TestUsers.Session caller, UUID conversationId) {
        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/conversations/" + conversationId + "/messages?after=0&limit=100",
                HttpMethod.GET, new HttpEntity<>(testUsers.authorised(caller)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> bodies = new ArrayList<>();
        response.getBody().path("messages").forEach(m -> bodies.add(m.path("body").asText()));
        return bodies;
    }
}
