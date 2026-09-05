package site.syamdev.shush.conversation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.syamdev.shush.common.ApiException;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final ConversationParticipantRepository participants;
    private final Clock clock;

    ConversationService(ConversationRepository conversations,
                        ConversationParticipantRepository participants,
                        Clock clock) {
        this.conversations = conversations;
        this.participants = participants;
        this.clock = clock;
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
}
