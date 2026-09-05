package site.syamdev.shush.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import site.syamdev.shush.support.AbstractIT;

import static org.assertj.core.api.Assertions.assertThat;

class HealthIT extends AbstractIT {

    @Test
    void livenessReportsUp() {
        ResponseEntity<String> response = rest.getForEntity("/api/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void readinessReportsUpWhenTheDatastoresAreReachable() {
        ResponseEntity<String> response = rest.getForEntity("/api/health/ready", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }
}
