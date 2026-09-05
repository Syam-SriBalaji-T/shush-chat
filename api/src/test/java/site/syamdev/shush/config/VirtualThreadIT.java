package site.syamdev.shush.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.support.AbstractPostgresIT;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the locked decision from aim.md 4.1 -- Spring MVC on virtual threads, not WebFlux.
 * A silently-dropped {@code spring.threads.virtual.enabled} would leave the whole
 * connection-per-thread design resting on a platform thread pool.
 */
class VirtualThreadIT extends AbstractPostgresIT {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void requestsAreServedOnVirtualThreads() {
        assertThat(rest.getForObject("/test/thread-is-virtual", Boolean.class)).isTrue();
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
