package site.syamdev.shush.conversation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.common.CurrentUser;
import site.syamdev.shush.message.Message;
import site.syamdev.shush.message.MessageService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
class ConversationController {

    private final ConversationService conversations;
    private final MessageService messages;
    private final CurrentUser currentUser;

    ConversationController(ConversationService conversations, MessageService messages,
                           CurrentUser currentUser) {
        this.conversations = conversations;
        this.messages = messages;
        this.currentUser = currentUser;
    }

    /**
     * Cursor-based history. {@code before} is a {@code seq}, not an offset, so a page stays
     * stable while messages keep arriving.
     */
    @GetMapping("/{conversationId}/messages")
    HistoryResponse history(@PathVariable UUID conversationId,
                            @RequestParam(required = false) Long before,
                            @RequestParam(defaultValue = "50") int limit) {
        conversations.requireParticipant(conversationId, currentUser.requireId());
        MessageService.Page page = messages.history(conversationId, before, limit);
        return new HistoryResponse(page.messages().stream().map(MessageView::of).toList(), page.nextBefore());
    }

    record MessageView(UUID id, UUID conversationId, UUID senderId, long seq, String kind,
                       String body, String mediaKey, UUID clientMsgId, Instant createdAt) {

        static MessageView of(Message message) {
            return new MessageView(message.getId(), message.getConversationId(), message.getSenderId(),
                    message.getSeq(), message.getKind().wireValue(), message.getBody(),
                    message.getMediaKey(), message.getClientMsgId(), message.getCreatedAt());
        }
    }

    record HistoryResponse(List<MessageView> messages, Long nextBefore) {}
}
