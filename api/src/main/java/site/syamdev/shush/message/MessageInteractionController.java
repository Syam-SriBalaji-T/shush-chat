package site.syamdev.shush.message;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.common.CurrentUser;

import java.util.UUID;

@RestController
@RequestMapping("/api/messages")
class MessageInteractionController {

    private final MessageInteractionService interactions;
    private final CurrentUser currentUser;

    MessageInteractionController(MessageInteractionService interactions, CurrentUser currentUser) {
        this.interactions = interactions;
        this.currentUser = currentUser;
    }

    /** One reaction per person per message, so this replaces rather than adds. */
    @PutMapping("/{messageId}/reaction")
    void react(@PathVariable UUID messageId, @Valid @RequestBody ReactionRequest request) {
        interactions.react(messageId, currentUser.requireId(), request.emoji());
    }

    @DeleteMapping("/{messageId}/reaction")
    void unreact(@PathVariable UUID messageId) {
        interactions.react(messageId, currentUser.requireId(), null);
    }

    /** Yours only: taking your own words back is a different act from hiding somebody else's. */
    @DeleteMapping("/{messageId}")
    void deleteForEveryone(@PathVariable UUID messageId) {
        interactions.deleteForEveryone(messageId, currentUser.requireId());
    }

    /** Anyone's, and nobody is told. */
    @PostMapping("/{messageId}/hide")
    void hideForMe(@PathVariable UUID messageId) {
        interactions.hideForMe(messageId, currentUser.requireId());
    }

    record ReactionRequest(@Size(max = 16) String emoji) {}
}
