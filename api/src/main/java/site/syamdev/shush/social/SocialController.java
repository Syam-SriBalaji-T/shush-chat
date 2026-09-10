package site.syamdev.shush.social;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.common.CurrentUser;
import site.syamdev.shush.conversation.Conversation;
import site.syamdev.shush.conversation.ConversationService;
import site.syamdev.shush.presence.PresenceService;
import site.syamdev.shush.user.User;
import site.syamdev.shush.user.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
class SocialController {

    private final SocialService social;
    private final PresenceService presence;
    private final UserRepository users;
    private final ConversationService conversations;
    private final CurrentUser currentUser;

    SocialController(SocialService social, PresenceService presence, UserRepository users,
                     ConversationService conversations, CurrentUser currentUser) {
        this.social = social;
        this.presence = presence;
        this.users = users;
        this.conversations = conversations;
        this.currentUser = currentUser;
    }

    @PostMapping("/conversations/{conversationId}/friend-request")
    FriendRequestView requestFriend(@PathVariable UUID conversationId) {
        UUID callerId = currentUser.requireId();
        return FriendRequestView.of(social.requestFriend(conversationId, callerId),
                users.findById(callerId).map(User::getDisplayName).orElse(null));
    }

    @GetMapping("/friend-requests")
    List<FriendRequestView> pending() {
        List<FriendRequest> requests = social.pendingFor(currentUser.requireId());
        // Their name, not "Someone". Deciding whether to keep a person you just talked to is
        // impossible if the app will not say which person it means -- and it already knows.
        Map<UUID, String> names = users.findAllById(
                        requests.stream().map(FriendRequest::getFromUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getDisplayName));
        return requests.stream()
                .map(request -> FriendRequestView.of(request, names.get(request.getFromUserId())))
                .toList();
    }

    @PostMapping("/friend-requests/{requestId}/accept")
    void accept(@PathVariable UUID requestId) {
        social.accept(requestId, currentUser.requireId());
    }

    /** Returns nothing and tells nobody. The sender simply never hears back. */
    @PostMapping("/friend-requests/{requestId}/decline")
    void decline(@PathVariable UUID requestId) {
        social.decline(requestId, currentUser.requireId());
    }

    /**
     * The friends list, with who is online right now. Presence for the whole list comes back in
     * one round trip rather than one call per friend.
     */
    @GetMapping("/friends")
    List<FriendView> friends() {
        UUID callerId = currentUser.requireId();
        List<Friendship> friendships = social.friendshipsOf(callerId);

        List<UUID> friendIds = friendships.stream()
                .map(friendship -> friendship.otherThan(callerId))
                .toList();
        Map<UUID, Boolean> online = presence.onlineAmong(friendIds);
        Map<UUID, User> byId = users.findAllById(friendIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, Integer> unread = conversations.unreadCountsFor(callerId,
                friendships.stream().map(Friendship::getConversationId).toList());

        return friendships.stream().map(friendship -> {
            UUID friendId = friendship.otherThan(callerId);
            User friend = byId.get(friendId);
            return new FriendView(friendId,
                    friend == null ? null : friend.getDisplayName(),
                    friendship.getConversationId(),
                    Boolean.TRUE.equals(online.get(friendId)),
                    friend == null ? null : friend.getLastSeenAt(),
                    unread.getOrDefault(friendship.getConversationId(), 0));
        }).toList();
    }

    /** Undoes keeping somebody, which also makes them matchable again. */
    @DeleteMapping("/friends/{userId}")
    void unfriend(@PathVariable UUID userId) {
        social.unfriend(currentUser.requireId(), userId);
    }

    @PostMapping("/blocks/{userId}")
    void block(@PathVariable UUID userId) {
        social.block(currentUser.requireId(), userId);
    }

    @DeleteMapping("/blocks/{userId}")
    void unblock(@PathVariable UUID userId) {
        social.unblock(currentUser.requireId(), userId);
    }

    @PostMapping("/reports")
    void report(@Valid @RequestBody ReportRequest request) {
        social.report(currentUser.requireId(), request.reportedId(), request.conversationId(),
                request.reason());
    }

    @PostMapping("/invites")
    InviteView createInvite() {
        InviteLink invite = social.createInvite(currentUser.requireId());
        return new InviteView(invite.getCode(), invite.getExpiresAt());
    }

    @PostMapping("/invites/{code}/accept")
    AcceptedInvite acceptInvite(@PathVariable String code) {
        Conversation conversation = social.acceptInvite(code, currentUser.requireId());
        return new AcceptedInvite(conversation.getId());
    }

    record ReportRequest(@NotNull UUID reportedId, UUID conversationId,
                         @NotBlank @Size(max = 500) String reason) {
    }

    record FriendRequestView(UUID id, UUID conversationId, UUID fromUserId, String fromDisplayName,
                             UUID toUserId, String status, Instant expiresAt) {

        static FriendRequestView of(FriendRequest request, String fromDisplayName) {
            return new FriendRequestView(request.getId(), request.getConversationId(),
                    request.getFromUserId(), fromDisplayName, request.getToUserId(),
                    request.getStatus().wireValue(), request.getExpiresAt());
        }
    }

    record FriendView(UUID userId, String displayName, UUID conversationId, boolean online,
                      Instant lastSeenAt, int unreadCount) {
    }

    record InviteView(String code, Instant expiresAt) {}

    record AcceptedInvite(UUID conversationId) {}
}
