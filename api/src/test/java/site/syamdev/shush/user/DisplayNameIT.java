package site.syamdev.shush.user;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import site.syamdev.shush.support.AbstractIT;
import site.syamdev.shush.support.TestUsers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayNameIT extends AbstractIT {

    @Test
    void aCustomNameIsRefusedWhileAnonymousAndAcceptedOnceSaved() {
        TestUsers.Session alice = testUsers.newAnonymous();

        ResponseEntity<JsonNode> refused = putName(alice, "Alice");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().path("code").asText()).isEqualTo("account_required");

        signUp(alice);

        ResponseEntity<JsonNode> accepted = putName(alice, "Alice");
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody().path("displayName").asText()).isEqualTo("Alice");
        assertThat(accepted.getBody().path("id").asText()).isEqualTo(alice.userId().toString());
    }

    /**
     * Assigned names are the only kind an anonymous visitor can have, so a saved account must
     * not be able to mint something indistinguishable from one.
     */
    @Test
    void aNameShapedLikeAnAssignedOneIsRefused() {
        TestUsers.Session alice = testUsers.newAnonymous();
        signUp(alice);

        ResponseEntity<JsonNode> refused = putName(alice, "Quiet Otter");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("code").asText()).isEqualTo("reserved_name_format");
    }

    @Test
    void aNameAlreadyInUseIsRefused() {
        TestUsers.Session first = testUsers.newAnonymous();
        TestUsers.Session second = testUsers.newAnonymous();
        signUp(first);
        signUp(second);

        String wanted = "Shared " + UUID.randomUUID().toString().substring(0, 6);
        assertThat(putName(first, wanted).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> clash = putName(second, wanted);
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(clash.getBody().path("code").asText()).isEqualTo("name_taken");
    }

    @Test
    void tooShortAndTooLongNamesAreRefused() {
        TestUsers.Session alice = testUsers.newAnonymous();
        signUp(alice);

        assertThat(putName(alice, "ab").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(putName(alice, "x".repeat(25)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shufflingGivesANewNameAndIsRateLimited() {
        TestUsers.Session alice = testUsers.newAnonymous();

        ResponseEntity<JsonNode> shuffled = rest.exchange("/api/me/shuffle-name", HttpMethod.POST,
                new HttpEntity<>(testUsers.authorised(alice)), JsonNode.class);

        assertThat(shuffled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(shuffled.getBody().path("displayName").asText())
                .matches("[A-Z][a-z]+ [A-Z][a-z]+( \\d+)?")
                .isNotEqualTo(alice.displayName());

        // Immediately again: a person tapping a button never sees this, a script always does.
        ResponseEntity<JsonNode> tooFast = rest.exchange("/api/me/shuffle-name", HttpMethod.POST,
                new HttpEntity<>(testUsers.authorised(alice)), JsonNode.class);
        assertThat(tooFast.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(tooFast.getBody().path("code").asText()).isEqualTo("shuffling_too_fast");
    }

    private void signUp(TestUsers.Session session) {
        ResponseEntity<JsonNode> response = rest.exchange("/api/auth/signup", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "email", "name-" + UUID.randomUUID() + "@example.com",
                        "password", "a-long-enough-password"), testUsers.authorised(session)),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<JsonNode> putName(TestUsers.Session session, String displayName) {
        return rest.exchange("/api/me/name", HttpMethod.PUT,
                new HttpEntity<>(Map.of("displayName", displayName), testUsers.authorised(session)),
                JsonNode.class);
    }
}
