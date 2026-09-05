package site.syamdev.shush.conversation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.common.ApiException;
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
     * Unread counts come from the maintained counter, never from a {@code COUNT(*)} at read
     * time -- that is the query that collapses first as a conversation grows (plan.md 3.5).
     */
    @GetMapping("/{conversationId}")
    ConversationView conversation(@PathVariable UUID conversationId) {
        ConversationService.View view = conversations.view(conversationId, currentUser.requireId());
        return new ConversationView(
                view.conversation().getId(),
                view.conversation().getKind().wireValue(),
                view.conversation().getState().wireValue(),
                view.me().getUnreadCount(),
                view.me().getReadCursorSeq(),
                view.others().stream()
                        .map(other -> new ParticipantView(other.getUserId(), other.getReadCursorSeq(),
                                other.getLeftAt() != null))
                        .toList());
    }

    /**
     * Cursor-based history. {@code before} is a {@code seq}, not an offset, so a page stays
     * stable while messages keep arriving.
     */
    @GetMapping("/{conversationId}/messages")
    HistoryResponse history(@PathVariable UUID conversationId,
                            @RequestParam(required = false) Long before,
                            @RequestParam(required = false) Long after,
                            @RequestParam(defaultValue = "50") int limit) {
        conversations.requireParticipant(conversationId, currentUser.requireId());

        if (before != null && after != null) {
            throw ApiException.badRequest("conflicting_cursors",
                    "pass either before or after, not both");
        }

        // `after` is the resume path -- a client that reconnects asks for what it missed, in
        // order. `before` is scrollback. They walk the same index in opposite directions.
        MessageService.Page page = after != null
                ? messages.since(conversationId, after, limit)
                : messages.history(conversationId, before, limit);

        List<MessageView> view = page.messages().stream().map(MessageView::of).toList();
        return after != null
                ? new HistoryResponse(view, null, page.nextCursor())
                : new HistoryResponse(view, page.nextCursor(), null);
    }

    record MessageView(UUID id, UUID conversationId, UUID senderId, long seq, String kind,
                       String body, String mediaKey, UUID clientMsgId, Instant createdAt) {

        static MessageView of(Message message) {
            return new MessageView(message.getId(), message.getConversationId(), message.getSenderId(),
                    message.getSeq(), message.getKind().wireValue(), message.getBody(),
                    message.getMediaKey(), message.getClientMsgId(), message.getCreatedAt());
        }
    }

    record HistoryResponse(List<MessageView> messages, Long nextBefore, Long nextAfter) {}

    record ParticipantView(UUID userId, long readCursorSeq, boolean hasLeft) {}

    record ConversationView(UUID id, String kind, String state, int unreadCount,
                            long readCursorSeq, List<ParticipantView> others) {
    }
}
