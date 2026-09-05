package site.syamdev.shush.spike;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import site.syamdev.shush.support.AbstractPostgresIT;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EchoApiIT extends AbstractPostgresIT {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void echoesTheMessageBack() {
        ResponseEntity<Map> response =
                rest.postForEntity("/api/echo", Map.of("message", "hello shush"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "hello shush");
        assertThat(response.getBody()).containsKey("at");
    }

    @Test
    void rejectsABlankMessage() {
        ResponseEntity<String> response =
                rest.postForEntity("/api/echo", Map.of("message", " "), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
