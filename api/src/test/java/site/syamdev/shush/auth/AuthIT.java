package site.syamdev.shush.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import site.syamdev.shush.support.AbstractIT;
import site.syamdev.shush.support.TestUsers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIT extends AbstractIT {

    @Test
    void anonymousAuthIssuesAWorkingJwt() {
        TestUsers.Session session = testUsers.newAnonymous();

        assertThat(session.jwt()).isNotBlank();
        assertThat(session.deviceToken()).isNotBlank();
        assertThat(session.displayName()).matches("[A-Z][a-z]+ [A-Z][a-z]+( \\d+)?");

        ResponseEntity<JsonNode> me = rest.exchange("/api/me",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(testUsers.authorised(session)), JsonNode.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().path("id").asText()).isEqualTo(session.userId().toString());
        assertThat(me.getBody().path("anonymous").asBoolean()).isTrue();
    }

    @Test
    void theSameDeviceTokenReturnsTheSameUser() {
        TestUsers.Session first = testUsers.newAnonymous();

        JsonNode resumed = rest.postForObject("/api/auth/device",
                Map.of("token", first.deviceToken()), JsonNode.class);

        assertThat(resumed.path("user").path("id").asText()).isEqualTo(first.userId().toString());
        assertThat(resumed.path("user").path("displayName").asText()).isEqualTo(first.displayName());
        // A fresh JWT, but the same identity behind it.
        assertThat(resumed.path("jwt").asText()).isNotBlank();
    }

    @Test
    void anUnknownDeviceTokenIsRejected() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/auth/device",
                Map.of("token", "not-a-real-token"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().path("code").asText()).isEqualTo("unknown_device_token");
    }

    @Test
    void anUnauthenticatedRequestIsRejected() {
        assertThat(rest.getForEntity("/api/me", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
