package site.syamdev.shush.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.support.AbstractPostgresIT;
import site.syamdev.shush.support.TestUsers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the locked decision from aim.md 4.1 -- Spring MVC on virtual threads, not WebFlux.
 * A silently-dropped {@code spring.threads.virtual.enabled} would leave the whole
 * connection-per-thread design resting on a platform thread pool.
 */
class VirtualThreadIT extends AbstractPostgresIT {

    @Test
    void requestsAreServedOnVirtualThreads() {
        TestUsers.Session session = testUsers.newAnonymous();

        ResponseEntity<Boolean> response = rest.exchange("/test/thread-is-virtual", HttpMethod.GET,
                new HttpEntity<>(testUsers.authorised(session)), Boolean.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isTrue();
    }

    @TestConfiguration
    static class ThreadProbeConfig {

        @Bean
        ThreadProbeController threadProbeController() {
            return new ThreadProbeController();
        }
    }

    @RestController
    static class ThreadProbeController {

        @GetMapping("/test/thread-is-virtual")
        boolean isVirtual() {
            return Thread.currentThread().isVirtual();
        }
    }
}
