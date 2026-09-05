package site.syamdev.shush.user;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import site.syamdev.shush.support.AbstractPostgresIT;
import site.syamdev.shush.support.TestUsers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InterestIT extends AbstractPostgresIT {

    @Test
    void aFirstTimeVisitorSeesTheFiveMostPopular() {
        JsonNode body = rest.getForObject("/api/interests", JsonNode.class);

        assertThat(body.path("suggested")).hasSize(5);
        assertThat(body.path("fromHistory").asBoolean()).isFalse();
        assertThat(slugsOf(body.path("suggested")))
                .containsExactly("music", "gaming", "movies", "books", "food");
        assertThat(body.path("all").size()).isGreaterThan(5);
    }

    @Test
    void aReturningVisitorSeesTheirOwnInterests() {
        TestUsers.Session session = testUsers.newAnonymous();

        ResponseEntity<Void> saved = rest.exchange("/api/interests/mine", HttpMethod.PUT,
                new HttpEntity<>(Map.of("interestIds", List.of(21, 25)), testUsers.authorised(session)),
                Void.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> body = rest.exchange("/api/interests", HttpMethod.GET,
                new HttpEntity<>(testUsers.authorised(session)), JsonNode.class);

        assertThat(body.getBody().path("fromHistory").asBoolean()).isTrue();
        assertThat(slugsOf(body.getBody().path("suggested")))
                .containsExactlyInAnyOrder("space", "philosophy");
    }

    private static List<String> slugsOf(JsonNode array) {
        List<String> slugs = new ArrayList<>();
        array.forEach(node -> slugs.add(node.path("slug").asText()));
        return slugs;
    }
}
