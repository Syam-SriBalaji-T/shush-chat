package site.syamdev.shush.message;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import site.syamdev.shush.common.ApiException;
import site.syamdev.shush.conversation.ConversationParticipantRepository;
import site.syamdev.shush.conversation.ConversationRepository;

import java.time.Clock;
import java.util.UUID;

/**
 * The only thing that inserts into {@code messages}. Separated from {@link MessageService} so
 * the transaction boundary is a real proxy boundary -- {@link MessageService} has to be able
 * to let this transaction roll back and then read committed state afterwards.
 *
 * <p>In Phase 2 the caller becomes the {@code chat-writer} consumer instead of the request
 * thread. The rules here are unchanged by that move, which is the point of isolating them.
 */
@Component
class MessageWriter {

    private final MessageRepository messages;
    private final ConversationRepository conversations;
    private final ConversationParticipantRepository participants;
    private final Clock clock;

    MessageWriter(MessageRepository messages, ConversationRepository conversations,
                  ConversationParticipantRepository participants, Clock clock) {
        this.messages = messages;
        this.conversations = conversations;
        this.participants = participants;
        this.clock = clock;
    }

    @Transactional
    Message write(UUID conversationId, UUID senderId, UUID clientMsgId,
                  Message.Kind kind, String body, String mediaKey, Long replyToSeq) {
        Long seq = conversations.claimNextSeq(conversationId);
        if (seq == null) {
            throw ApiException.notFound("unknown_conversation", "no such conversation");
        }

        Message message = new Message(UUID.randomUUID(), conversationId, senderId, seq,
                kind, body, mediaKey, clientMsgId, clock.instant(), replyToSeq);

        int inserted = messages.insertIfAbsent(message.getId(), conversationId, senderId, seq,
                kind.wireValue(), body, mediaKey, clientMsgId, message.getCreatedAt(), replyToSeq);
        if (inserted == 0) {
            throw new DuplicateMessageException();
        }

        participants.incrementUnreadForRecipients(conversationId, senderId);
        return message;
    }
}
