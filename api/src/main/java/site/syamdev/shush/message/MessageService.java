package site.syamdev.shush.message;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.syamdev.shush.common.ApiException;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Phase 1 appends from the request path. Phase 2 moves the call behind Redpanda so the sole
 * caller becomes the chat-writer consumer; the sequencing and dedup rules do not change.
 */
@Service
public class MessageService {

    public static final int MAX_PAGE_SIZE = 100;
    static final int MAX_BODY_LENGTH = 4000;

    private final MessageWriter writer;
    private final MessageRepository messages;

    MessageService(MessageWriter writer, MessageRepository messages) {
        this.writer = writer;
        this.messages = messages;
    }

    /**
     * @return the persisted message, flagged as a duplicate when this {@code clientMsgId} had
     *         already been accepted for this sender in this conversation
     */
    public Append append(UUID conversationId, UUID senderId, UUID clientMsgId,
                         Message.Kind kind, String body, String mediaKey) {
        validate(kind, body);
        try {
            return new Append(writer.write(conversationId, senderId, clientMsgId, kind, body, mediaKey), false);
        } catch (DuplicateMessageException duplicate) {
            // The write rolled back, releasing its sequence number. The winner is committed.
            return new Append(existing(conversationId, senderId, clientMsgId), true);
        }
    }

    private static void validate(Message.Kind kind, String body) {
        if (kind == Message.Kind.TEXT && (body == null || body.isBlank())) {
            throw ApiException.badRequest("empty_body", "a text message needs a body");
        }
        if (body != null && body.length() > MAX_BODY_LENGTH) {
            throw ApiException.badRequest("body_too_long",
                    "a message body is limited to " + MAX_BODY_LENGTH + " characters");
        }
    }

    private Message existing(UUID conversationId, UUID senderId, UUID clientMsgId) {
        return messages.findByConversationIdAndSenderIdAndClientMsgId(conversationId, senderId, clientMsgId)
                .orElseThrow(() -> new IllegalStateException(
                        "dedup constraint rejected an insert but no committed row matches it"));
    }

    /**
     * One page of history, oldest-first within the page, walking backwards from {@code beforeSeq}.
     * Cursoring on {@code seq} rather than an offset keeps pagination stable while new messages
     * arrive underneath the reader.
     */
    @Transactional(readOnly = true)
    public Page history(UUID conversationId, Long beforeSeq, int limit) {
        int size = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        long cursor = beforeSeq == null ? Long.MAX_VALUE : beforeSeq;

        List<Message> found = messages.findByConversationIdAndSeqLessThanOrderBySeqDesc(
                conversationId, cursor, Limit.of(size + 1));

        boolean more = found.size() > size;
        List<Message> window = (more ? found.subList(0, size) : found).stream()
                .sorted(Comparator.comparingLong(Message::getSeq))
                .toList();

        Long nextBefore = window.isEmpty() || !more ? null : window.getFirst().getSeq();
        return new Page(window, nextBefore);
    }

    public record Append(Message message, boolean duplicate) {}

    public record Page(List<Message> messages, Long nextBefore) {}
}
