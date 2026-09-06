package site.syamdev.shush.realtime;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import site.syamdev.shush.message.Message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ServerFrame.Ack.class, name = "ack"),
        @JsonSubTypes.Type(value = ServerFrame.MessageFrame.class, name = "message"),
        @JsonSubTypes.Type(value = ServerFrame.Error.class, name = "error"),
        @JsonSubTypes.Type(value = ServerFrame.Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = ServerFrame.Presence.class, name = "presence"),
        @JsonSubTypes.Type(value = ServerFrame.Typing.class, name = "typing"),
        @JsonSubTypes.Type(value = ServerFrame.ReadReceipt.class, name = "read"),
        @JsonSubTypes.Type(value = ServerFrame.Left.class, name = "left"),
        @JsonSubTypes.Type(value = ServerFrame.Matched.class, name = "matched"),
        @JsonSubTypes.Type(value = ServerFrame.FriendRequested.class, name = "friendRequested"),
        @JsonSubTypes.Type(value = ServerFrame.FriendRequestAccepted.class, name = "friendRequestAccepted")
})
public sealed interface ServerFrame {

    /**
     * Two-stage on purpose. {@code sent} means the log accepted it and it will not be lost;
     * {@code delivered} means it is committed to Postgres with a sequence number, which is the
     * first moment anything can say where it sits in the conversation's order.
     *
     * @param seq null on {@code sent} -- no sequence number exists until the writer assigns one
     */
    record Ack(UUID clientMsgId, String status, UUID messageId, Long seq, boolean duplicate)
            implements ServerFrame {

        static Ack sent(UUID clientMsgId) {
            return new Ack(clientMsgId, "sent", null, null, false);
        }

        static Ack delivered(Message message, boolean duplicate) {
            return new Ack(message.getClientMsgId(), "delivered", message.getId(),
                    message.getSeq(), duplicate);
        }
    }

    record MessageFrame(UUID conversationId, UUID messageId, long seq, UUID senderId,
                        String kind, String body, String mediaKey, UUID clientMsgId,
                        Instant createdAt) implements ServerFrame {

        static MessageFrame of(Message message) {
            return new MessageFrame(message.getConversationId(), message.getId(), message.getSeq(),
                    message.getSenderId(), message.getKind().wireValue(), message.getBody(),
                    message.getMediaKey(), message.getClientMsgId(), message.getCreatedAt());
        }
    }

    record Error(String code, String message, UUID clientMsgId) implements ServerFrame {}

    /**
     * Sent once, immediately after the socket opens.
     *
     * <p>{@code nodeId} is diagnostic only -- nothing addresses a node, and a client that
     * reconnects will usually land somewhere else. It exists so an operator, and the load
     * harness, can tell that a run genuinely spanned replicas instead of quietly proving
     * nothing on one.
     */
    record Hello(UUID userId, String nodeId) implements ServerFrame {}

    /**
     * Someone's connection came or went. Distinct from {@link Left}: going offline is not
     * leaving, the conversation stays open, and anything sent meanwhile is waiting when they
     * return (pre-plan.md 3).
     *
     * @param lastSeenAt only meaningful when {@code online} is false
     */
    record Presence(UUID conversationId, UUID userId, boolean online, Instant lastSeenAt)
            implements ServerFrame {
    }

    record Typing(UUID conversationId, UUID userId) implements ServerFrame {}

    /** @param seq the highest sequence number that user has read */
    record ReadReceipt(UUID conversationId, UUID userId, long seq) implements ServerFrame {}

    /** Deliberately gone. The conversation is over; this is not a reconnect. */
    record Left(UUID conversationId, UUID userId) implements ServerFrame {}

    /**
     * @param sharedInterestIds null when the patience window ran out
     * @param randomMatch       stated plainly, because the conversation header says which of the
     *                          two this was and presenting a random match as an interest match
     *                          is a lie the user notices as soon as they start talking
     */
    record Matched(UUID conversationId, UUID withUserId, List<Short> sharedInterestIds,
                   boolean randomMatch) implements ServerFrame {
    }

    record FriendRequested(UUID conversationId, UUID requestId, UUID fromUserId) implements ServerFrame {}

    /**
     * Only acceptance is announced. A decline says nothing at all -- the sender simply never
     * hears back, which is the whole point of it being silent (pre-plan.md 6).
     */
    record FriendRequestAccepted(UUID conversationId, UUID requestId, UUID byUserId)
            implements ServerFrame {
    }
}
