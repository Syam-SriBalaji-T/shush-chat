package site.syamdev.shush.conversation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.syamdev.shush.common.ApiException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final ConversationParticipantRepository participants;
    private final Clock clock;
    private final Duration purgeAfterEnding;

    ConversationService(ConversationRepository conversations,
                        ConversationParticipantRepository participants,
                        Clock clock,
                        @Value("${shush.conversation.purge-after-ending}") Duration purgeAfterEnding) {
        this.conversations = conversations;
        this.participants = participants;
        this.clock = clock;
        this.purgeAfterEnding = purgeAfterEnding;
    }

    @Transactional
    public Conversation create(Conversation.Kind kind, UUID firstUserId, UUID secondUserId) {
        if (firstUserId.equals(secondUserId)) {
            throw ApiException.badRequest("self_conversation", "a conversation needs two distinct users");
        }
        Conversation conversation = conversations.save(
                new Conversation(UUID.randomUUID(), kind, clock.instant()));
        participants.saveAll(List.of(
                new ConversationParticipant(conversation.getId(), firstUserId),
                new ConversationParticipant(conversation.getId(), secondUserId)));
        return conversation;
    }

    @Transactional(readOnly = true)
    public void requireParticipant(UUID conversationId, UUID userId) {
        if (!participants.existsByConversationIdAndUserId(conversationId, userId)) {
            // Deliberately not "no such conversation": membership and existence are the same
            // answer to a caller who is not in it.
            throw ApiException.forbidden("not_a_participant", "you are not in this conversation");
        }
    }

    @Transactional(readOnly = true)
    public List<UUID> participantIds(UUID conversationId) {
        return participants.findByConversationId(conversationId).stream()
                .map(ConversationParticipant::getUserId)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Counterpart> activeCounterparts(UUID userId) {
        return participants.findActiveCounterparts(userId).stream()
                .map(row -> new Counterpart(row.getConversationId(), row.getUserId()))
                .toList();
    }

    /**
     * @return true if the cursor actually moved. A repeated or stale read is not an error and
     *         is simply not announced -- read receipts that fire on every scroll are noise.
     */
    @Transactional
    public boolean markRead(UUID conversationId, UUID userId, long seq) {
        return participants.advanceReadCursor(conversationId, userId, seq) > 0;
    }

    /**
     * Deliberately leaving, as distinct from losing connection.
     *
     * <p>The conversation ends and is scheduled for purge: a stranger conversation nobody wanted
     * to keep does not survive (pre-plan.md 6). Phase 6 clears {@code purgeAfter} when a friend
     * request exists, which is what "conversations only survive if at least one person wanted
     * them to" means in practice.
     *
     * @return true if this call ended it, false if it had already ended
     */
    @Transactional
    public boolean leave(UUID conversationId, UUID userId) {
        Instant now = clock.instant();
        if (participants.markLeft(conversationId, userId, now) == 0) {
            return false;
        }
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> ApiException.notFound("unknown_conversation", "no such conversation"));
        if (conversation.getState() != Conversation.State.ACTIVE) {
            return false;
        }
        conversation.end(now, now.plus(purgeAfterEnding));
        return true;
    }

    @Transactional(readOnly = true)
    public View view(UUID conversationId, UUID callerId) {
        requireParticipant(conversationId, callerId);
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> ApiException.notFound("unknown_conversation", "no such conversation"));

        List<ConversationParticipant> rows = participants.findByConversationId(conversationId);
        ConversationParticipant me = rows.stream()
                .filter(row -> row.getUserId().equals(callerId))
                .findFirst()
                .orElseThrow(() -> ApiException.forbidden("not_a_participant",
                        "you are not in this conversation"));

        return new View(conversation, me, rows.stream()
                .filter(row -> !row.getUserId().equals(callerId))
                .toList());
    }

    public record Counterpart(UUID conversationId, UUID userId) {}

    public record View(Conversation conversation,
                       ConversationParticipant me,
                       List<ConversationParticipant> others) {
    }
}
