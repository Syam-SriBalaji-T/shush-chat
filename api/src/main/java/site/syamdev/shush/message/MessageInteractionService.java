package site.syamdev.shush.message;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.syamdev.shush.common.AfterCommit;
import site.syamdev.shush.common.ApiException;
import site.syamdev.shush.conversation.ConversationService;
import site.syamdev.shush.realtime.BackplanePublisher;
import site.syamdev.shush.realtime.ServerFrame;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reacting to a message, and the two different things "delete" means.
 *
 * <p>None of this goes through the log. Redpanda is the write-ahead log for <em>messages</em>,
 * because a message needs an order and a sequence number and exactly one writer. A reaction has
 * no position in the conversation and a deletion changes a row that already has one — routing
 * them through the log would buy nothing and put two kinds of thing in a topic whose partition
 * key means one of them. They are ordinary writes, and the fanout still goes through Redis, so
 * the "one delivery path" invariant is untouched.
 */
@Service
public class MessageInteractionService {

    /**
     * An allowlist, and a short one. The reaction is stored as text and rendered by every
     * client, so accepting arbitrary strings here would make this a way to put anything into
     * somebody else's message list.
     */
    private static final Set<String> ALLOWED = Set.of("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "🎉");

    private final MessageRepository messages;
    private final MessageReactionRepository reactions;
    private final MessageHideRepository hides;
    private final ConversationService conversations;
    private final BackplanePublisher backplane;
    private final Clock clock;

    MessageInteractionService(MessageRepository messages, MessageReactionRepository reactions,
                              MessageHideRepository hides, ConversationService conversations,
                              BackplanePublisher backplane, Clock clock) {
        this.messages = messages;
        this.reactions = reactions;
        this.hides = hides;
        this.conversations = conversations;
        this.backplane = backplane;
        this.clock = clock;
    }

    /** @param emoji null takes the reaction back */
    @Transactional
    public void react(UUID messageId, UUID userId, String emoji) {
        Message message = require(messageId, userId);
        if (emoji != null && !ALLOWED.contains(emoji)) {
            throw ApiException.badRequest("unsupported_reaction", "that is not one of the reactions");
        }

        if (emoji == null) {
            reactions.deleteByMessageIdAndUserId(messageId, userId);
        } else {
            reactions.findByMessageIdAndUserId(messageId, userId)
                    .ifPresentOrElse(
                            existing -> existing.changeTo(emoji, clock.instant()),
                            () -> reactions.save(
                                    new MessageReaction(messageId, userId, emoji, clock.instant())));
        }

        publish(message.getConversationId(),
                new ServerFrame.Reaction(message.getConversationId(), message.getSeq(), userId, emoji));
    }

    /**
     * Deletes for both people. Only the sender may, which is the whole difference between this
     * and hiding: taking your words back is yours to do, taking somebody else's is not.
     */
    @Transactional
    public void deleteForEveryone(UUID messageId, UUID userId) {
        Message message = require(messageId, userId);
        if (!message.getSenderId().equals(userId)) {
            throw ApiException.forbidden("not_your_message",
                    "you can only delete your own message for everyone");
        }
        if (message.isDeleted()) {
            return;
        }
        message.deleteForEveryone(clock.instant());
        // The reactions go with it. A tally on a message nobody can read is noise.
        reactions.findByMessageIdIn(List.of(messageId)).forEach(reactions::delete);

        publish(message.getConversationId(),
                new ServerFrame.Deleted(message.getConversationId(), message.getSeq(), userId));
    }

    /** Hides one message from one person, and tells nobody. */
    @Transactional
    public void hideForMe(UUID messageId, UUID userId) {
        require(messageId, userId);
        if (!hides.existsByMessageIdAndUserId(messageId, userId)) {
            hides.save(new MessageHide(messageId, userId, clock.instant()));
        }
    }

    /** Which of these messages this person has hidden. One query, not one per message. */
    @Transactional(readOnly = true)
    public Set<UUID> hiddenFrom(UUID userId, Collection<UUID> messageIds) {
        if (messageIds.isEmpty()) {
            return Set.of();
        }
        return hides.findByUserIdAndMessageIdIn(userId, messageIds).stream()
                .map(MessageHide::getMessageId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Transactional(readOnly = true)
    public List<MessageReaction> reactionsFor(Collection<UUID> messageIds) {
        return messageIds.isEmpty() ? List.of() : reactions.findByMessageIdIn(messageIds);
    }

    private Message require(UUID messageId, UUID userId) {
        Message message = messages.findById(messageId)
                .orElseThrow(() -> ApiException.notFound("unknown_message", "no such message"));
        // Membership and existence are the same answer to somebody who is not in the
        // conversation, so this check has to come before anything is revealed about it.
        conversations.requireParticipant(message.getConversationId(), userId);
        return message;
    }

    /** After the commit: a client answers these by re-reading, and an uncommitted row is not there. */
    private void publish(UUID conversationId, ServerFrame frame) {
        List<UUID> participants = conversations.participantIds(conversationId);
        AfterCommit.run(() -> backplane.publish(participants, frame));
    }
}
