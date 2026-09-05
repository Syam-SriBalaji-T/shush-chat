package site.syamdev.shush.presence;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.common.ApiException;
import site.syamdev.shush.common.CurrentUser;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bulk presence lookup, which is how the friends list renders "who is online right now" in one
 * round trip instead of one per friend.
 */
@RestController
@RequestMapping("/api/presence")
class PresenceController {

    private static final int MAX_USERS_PER_QUERY = 200;

    private final PresenceService presence;
    private final CurrentUser currentUser;

    PresenceController(PresenceService presence, CurrentUser currentUser) {
        this.presence = presence;
        this.currentUser = currentUser;
    }

    @GetMapping
    Map<UUID, Boolean> online(@RequestParam List<UUID> userIds) {
        currentUser.requireId();
        if (userIds.size() > MAX_USERS_PER_QUERY) {
            throw ApiException.badRequest("too_many_users",
                    "ask about at most " + MAX_USERS_PER_QUERY + " users at a time");
        }
        return presence.onlineAmong(userIds);
    }
}
