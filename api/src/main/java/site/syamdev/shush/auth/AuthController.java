package site.syamdev.shush.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.common.CurrentUser;
import site.syamdev.shush.user.UserView;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;

    AuthController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
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

    /**
     * The only moment the product asks for an email, and it asks to *save* an account rather
     * than to create one (pre-plan.md 3, step 7). The caller must already be signed in, because
     * the whole point is attaching to the identity they have.
     */
    @PostMapping("/signup")
    SessionResponse signUp(@Valid @RequestBody SignUpRequest request) {
        return SessionResponse.of(
                authService.signUp(currentUser.requireId(), request.email(), request.password()));
    }

    @PostMapping("/login")
    SessionResponse logIn(@Valid @RequestBody LogInRequest request) {
        return SessionResponse.of(authService.logIn(request.email(), request.password()));
    }

    @PostMapping("/logout")
    void signOut(@RequestBody(required = false) DeviceRequest request) {
        authService.signOut(request == null ? null : request.token());
    }

    record DeviceRequest(@NotBlank String token) {}

    record SignUpRequest(@Email @NotBlank @Size(max = 254) String email,
                         @NotBlank @Size(min = 8, max = 128) String password) {}

    record LogInRequest(@NotBlank String email, @NotBlank String password) {}

    record SessionResponse(String token, String jwt, UserView user) {

        static SessionResponse of(AuthService.Session session) {
            return new SessionResponse(session.deviceToken(), session.jwt(), UserView.of(session.user()));
        }
    }
}
