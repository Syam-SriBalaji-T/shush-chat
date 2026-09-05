package site.syamdev.shush.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.user.UserView;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Three clicks to a conversation means no form here at all (pre-plan.md 2). */
    @PostMapping("/anonymous")
    SessionResponse anonymous() {
        return SessionResponse.of(authService.createAnonymous());
    }

    @PostMapping("/device")
    SessionResponse device(@Valid @RequestBody DeviceRequest request) {
        return SessionResponse.of(authService.resumeFromDevice(request.token()));
    }

    record DeviceRequest(@NotBlank String token) {}

    record SessionResponse(String token, String jwt, UserView user) {

        static SessionResponse of(AuthService.Session session) {
            return new SessionResponse(session.deviceToken(), session.jwt(), UserView.of(session.user()));
        }
    }
}
