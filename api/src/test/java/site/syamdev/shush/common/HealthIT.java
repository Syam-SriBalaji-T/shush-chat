package site.syamdev.shush.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import site.syamdev.shush.support.AbstractPostgresIT;

import static org.assertj.core.api.Assertions.assertThat;

class HealthIT extends AbstractPostgresIT {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthReportsUp() {
        ResponseEntity<String> response = rest.getForEntity("/api/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }
}
