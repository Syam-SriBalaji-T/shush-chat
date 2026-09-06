package site.syamdev.shush.common;

import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two probes, because they answer different questions and conflating them is an outage
 * amplifier.
 *
 * <p>{@code /api/health} is liveness: has this process finished starting and is it still
 * serving? It performs no dependency I/O at all. An earlier version delegated to the full
 * aggregate health, and under load the container probe timed out waiting on downstream checks
 * and declared a perfectly healthy node dead -- which is precisely the failure mode where you
 * least want to be shooting your own replicas.
 *
 * <p>{@code /api/health/ready} is readiness: should this node be sent traffic? It checks the
 * datastores. Both answer 503 rather than a green 200 with a sad body, because a load balancer
 * reads the status code.
 */
@RestController
@RequestMapping("/api/health")
class HealthController {

    private final HealthEndpoint healthEndpoint;

    HealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping
    ResponseEntity<HealthResponse> live() {
        return respond(healthEndpoint.healthForPath("liveness"));
    }

    @GetMapping("/ready")
    ResponseEntity<HealthResponse> ready() {
        return respond(healthEndpoint.healthForPath("readiness"));
    }

    private static ResponseEntity<HealthResponse> respond(HealthComponent health) {
        Status status = health == null ? Status.UNKNOWN : health.getStatus();
        HttpStatus httpStatus = Status.UP.equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(new HealthResponse(status.getCode()));
    }

    record HealthResponse(String status) {}
}
