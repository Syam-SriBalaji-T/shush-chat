package site.syamdev.shush.spike;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Phase 0 spike only. Deleted when Phase 1 introduces real endpoints. */
@RestController
@RequestMapping("/api")
class EchoController {

    @PostMapping("/echo")
    EchoResponse echo(@Valid @RequestBody EchoRequest request) {
        return new EchoResponse(request.message(), Instant.now());
    }

    record EchoRequest(@NotBlank @Size(max = 512) String message) {}

    record EchoResponse(String message, Instant at) {}
}
