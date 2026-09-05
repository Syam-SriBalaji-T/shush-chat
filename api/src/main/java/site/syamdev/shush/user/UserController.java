package site.syamdev.shush.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.common.ApiException;
import site.syamdev.shush.common.CurrentUser;

@RestController
@RequestMapping("/api/me")
class UserController {

    private final UserRepository users;
    private final CurrentUser currentUser;

    UserController(UserRepository users, CurrentUser currentUser) {
        this.users = users;
        this.currentUser = currentUser;
    }

    @GetMapping
    UserView me() {
        return users.findById(currentUser.requireId())
                .map(UserView::of)
                .orElseThrow(() -> ApiException.notFound("unknown_user", "no such user"));
    }
}
