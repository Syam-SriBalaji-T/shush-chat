package site.syamdev.shush.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.common.CurrentUser;

@RestController
@RequestMapping("/api/me")
class UserController {

    private final UserService userService;
    private final CurrentUser currentUser;

    UserController(UserService userService, CurrentUser currentUser) {
        this.userService = userService;
        this.currentUser = currentUser;
    }

    @GetMapping
    UserView me() {
        return UserView.of(userService.require(currentUser.requireId()));
    }

    @PostMapping("/shuffle-name")
    UserView shuffleName() {
        return UserView.of(userService.shuffleName(currentUser.requireId()));
    }

    @PutMapping("/name")
    UserView chooseName(@Valid @RequestBody NameRequest request) {
        return UserView.of(userService.chooseName(currentUser.requireId(), request.displayName()));
    }

    record NameRequest(@NotBlank String displayName) {}
}
