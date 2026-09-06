package site.syamdev.shush.conversation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.common.CurrentUser;

import java.util.UUID;

/**
 * Creates a conversation directly, standing in for matching until Phase 6. Off unless
 * {@code shush.dev-endpoints.enabled} is set, so it cannot be reached in a real deployment;
 * the bench harness turns it on deliberately.
 */
@RestController
@RequestMapping("/api/dev/conversations")
@ConditionalOnProperty(name = "shush.dev-endpoints.enabled", havingValue = "true")
class DevConversationController {

    private final ConversationService conversations;
    private final CurrentUser currentUser;

    DevConversationController(ConversationService conversations, CurrentUser currentUser) {
        this.conversations = conversations;
        this.currentUser = currentUser;
    }

    @PostMapping
    CreatedConversation create(@Valid @RequestBody CreateRequest request) {
        Conversation conversation = conversations.create(
                Conversation.Kind.STRANGER, currentUser.requireId(), request.otherUserId());
        return new CreatedConversation(conversation.getId());
    }

    record CreateRequest(@NotNull UUID otherUserId) {}

    record CreatedConversation(UUID conversationId) {}
}
