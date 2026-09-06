package site.syamdev.shush.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;

import java.util.Map;
import java.util.UUID;

/** Drives the real HTTP API to get sessions, rather than reaching past it into the services. */
public class TestUsers {

    private final TestRestTemplate rest;

    public TestUsers(TestRestTemplate rest) {
        this.rest = rest;
    }

    public Session newAnonymous() {
        JsonNode body = rest.postForObject("/api/auth/anonymous", null, JsonNode.class);
        return new Session(
                UUID.fromString(body.path("user").path("id").asText()),
                body.path("user").path("displayName").asText(),
                body.path("token").asText(),
                body.path("jwt").asText());
    }

    public UUID createConversation(Session caller, Session other) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(caller.jwt());
        JsonNode body = rest.postForObject("/api/dev/conversations",
                new HttpEntity<>(Map.of("otherUserId", other.userId().toString()), headers),
                JsonNode.class);
        return UUID.fromString(body.path("conversationId").asText());
    }

    public HttpHeaders authorised(Session session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(session.jwt());
        return headers;
    }

    public record Session(UUID userId, String displayName, String deviceToken, String jwt) {}
}
