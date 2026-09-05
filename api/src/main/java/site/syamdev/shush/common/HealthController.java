package site.syamdev.shush.common;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plain-JSON health for load balancers and the exit-criteria curl. It delegates to the
 * Actuator health endpoint rather than returning a constant, so a dead datastore is
 * actually reported instead of a green light over a broken node.
 */
@RestController
@RequestMapping("/api")
class HealthController {

    private final HealthEndpoint healthEndpoint;

    HealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/health")
    ResponseEntity<HealthResponse> health() {
        Status status = healthEndpoint.health().getStatus();
        HttpStatus httpStatus = Status.UP.equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(new HealthResponse(status.getCode()));
    }

    record HealthResponse(String status) {}
}
