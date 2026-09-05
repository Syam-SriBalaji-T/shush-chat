package site.syamdev.shush.presence;

import org.springframework.stereotype.Component;
import site.syamdev.shush.conversation.ConversationService;
import site.syamdev.shush.realtime.BackplanePublisher;
import site.syamdev.shush.realtime.ServerFrame;
import site.syamdev.shush.user.User;
import site.syamdev.shush.user.UserRepository;

import java.time.Instant;
import java.util.UUID;

/**
 * Tells the people who would actually notice.
 *
 * <p>Announcing to the counterparts of a user's open conversations, rather than broadcasting,
 * keeps presence traffic proportional to conversations rather than to users -- and a stranger
 * chat has exactly one person on the other side.
 */
@Component
public class PresenceAnnouncer {

    private final ConversationService conversations;
    private final BackplanePublisher backplane;
    private final UserRepository users;

    PresenceAnnouncer(ConversationService conversations, BackplanePublisher backplane,
                      UserRepository users) {
        this.conversations = conversations;
        this.backplane = backplane;
        this.users = users;
    }

    public void announceOnline(UUID userId) {
        announce(userId, true, null);
    }

    /**
     * "Gone offline", not "left" -- the conversation stays open because they might come back,
     * and anything sent meanwhile reaches them when they do (pre-plan.md 3).
     */
    public void announceOffline(UUID userId) {
        Instant lastSeenAt = users.findById(userId).map(User::getLastSeenAt).orElse(null);
        announce(userId, false, lastSeenAt);
    }

    private void announce(UUID userId, boolean online, Instant lastSeenAt) {
        conversations.activeCounterparts(userId).forEach(counterpart ->
                backplane.publish(counterpart.userId(),
                        new ServerFrame.Presence(counterpart.conversationId(), userId, online, lastSeenAt)));
    }
}
