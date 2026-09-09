package site.syamdev.shush.cors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import site.syamdev.shush.support.AbstractIT;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of the table is that a new frontend does not need a deployment, so the thing worth
 * testing is not that CORS works — it is that a row added to a running system takes effect.
 */
class CorsOriginsIT extends AbstractIT {

    private static final String NEW_FRONTEND = "http://localhost:4321";

    @Autowired
    private CorsOriginRepository origins;

    @Autowired
    private AllowedOrigins allowed;

    @AfterEach
    void removeTheTestRow() {
        origins.deleteById(NEW_FRONTEND);
    }

    @Test
    void anOriginIsAllowedOnceItsRowExists() {
        assertThat(preflight(NEW_FRONTEND).getStatusCode())
                .as("refused before anyone said it was allowed")
                .isEqualTo(HttpStatus.FORBIDDEN);

        origins.save(new CorsOrigin(NEW_FRONTEND, "added while running", Instant.now()));
        // The set is cached for a few seconds because it sits on the path of every preflight.
        // Nothing restarts here -- this is the same process, answering differently.
        forceRefresh();

        ResponseEntity<Void> answer = preflight(NEW_FRONTEND);
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answer.getHeaders().getAccessControlAllowOrigin())
                .as("the exact origin, never a wildcard")
                .isEqualTo(NEW_FRONTEND);
    }

    @Test
    void anOriginNobodyListedIsRefused() {
        assertThat(preflight("http://somewhere-else.example").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theTableIsNotAWayToTurnCorsOff() {
        origins.save(new CorsOrigin(NEW_FRONTEND, "added while running", Instant.now()));
        forceRefresh();

        assertThat(allowed.permits("http://not-this-one.example"))
                .as("one row allows one origin, not everything")
                .isFalse();
        assertThat(allowed.permits(NEW_FRONTEND)).isTrue();
    }

    /** The cache TTL is real time, so wait it out rather than pretend it is not there. */
    private void forceRefresh() {
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(30))
                .pollInterval(java.time.Duration.ofMillis(500))
                .until(() -> allowed.permits(NEW_FRONTEND));
    }

    /** TestRestTemplate resolves a relative path against the running server's random port. */
    private ResponseEntity<Void> preflight(String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, origin);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization");
        return rest.exchange("/api/friends", HttpMethod.OPTIONS, new HttpEntity<>(headers), Void.class);
    }
}
