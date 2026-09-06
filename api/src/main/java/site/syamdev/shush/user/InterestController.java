package site.syamdev.shush.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.common.CurrentUser;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/interests")
class InterestController {

    private final InterestService interestService;
    private final CurrentUser currentUser;

    InterestController(InterestService interestService, CurrentUser currentUser) {
        this.interestService = interestService;
        this.currentUser = currentUser;
    }

    /** Permitted anonymously so the interest screen renders before a token exists. */
    @GetMapping
    InterestsResponse list() {
        InterestService.Suggestions suggestions = interestService.suggestFor(optionalUserId());
        return new InterestsResponse(
                suggestions.suggested().stream().map(InterestView::of).toList(),
                suggestions.all().stream().map(InterestView::of).toList(),
                suggestions.fromHistory());
    }

    @PutMapping("/mine")
    void select(@Valid @RequestBody SelectionRequest request) {
        interestService.recordSelection(currentUser.requireId(), request.interestIds());
    }

    private UUID optionalUserId() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                ? UUID.fromString(token.getToken().getSubject())
                : null;
    }

    record SelectionRequest(@NotEmpty @Size(max = 10) List<Short> interestIds) {}

    record InterestView(short id, String slug, String label) {

        static InterestView of(Interest interest) {
            return new InterestView(interest.getId(), interest.getSlug(), interest.getLabel());
        }
    }

    record InterestsResponse(List<InterestView> suggested, List<InterestView> all, boolean fromHistory) {}
}
