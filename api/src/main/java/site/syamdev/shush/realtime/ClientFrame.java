package site.syamdev.shush.realtime;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

/**
 * Everything a client may send. Sealed so the handler's switch is exhaustive at compile time --
 * adding a frame type in a later phase cannot silently fall through to "unknown".
 *
 * <p>JSON is camelCase (see CLAUDE.md); Postgres stays snake_case. Mapping happens at the edge.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientFrame.Send.class, name = "send"),
        @JsonSubTypes.Type(value = ClientFrame.Read.class, name = "read"),
        @JsonSubTypes.Type(value = ClientFrame.Typing.class, name = "typing"),
        @JsonSubTypes.Type(value = ClientFrame.Leave.class, name = "leave"),
        @JsonSubTypes.Type(value = ClientFrame.Find.class, name = "find"),
        @JsonSubTypes.Type(value = ClientFrame.CancelFind.class, name = "cancelFind")
})
public sealed interface ClientFrame {

    /**
     * @param clientMsgId the idempotency key, stable across the client's retries of one logical
     *                    send. The server never generates it.
     */
    record Send(UUID conversationId, UUID clientMsgId, String kind, String body, String mediaKey)
            implements ClientFrame {
    }

    /** @param seq the highest sequence number the sender has now read */
    record Read(UUID conversationId, long seq) implements ClientFrame {}

    /**
     * Fires on keystrokes, so it is the highest-frequency frame in the protocol by a wide
     * margin. The client throttles to one per three seconds and the server enforces the same
     * bound rather than trusting it -- a naive implementation writes to the datastore on every
     * keypress and falls over.
     */
    record Typing(UUID conversationId) implements ClientFrame {}

    /**
     * Leaving for good, as distinct from losing connection. pre-plan.md 3 makes the two visibly
     * different to the other person, so they must be different frames.
     */
    record Leave(UUID conversationId) implements ClientFrame {}

    /**
     * @param patience 5 or 10 seconds of trying for a shared interest before settling for
     *                 anyone, or 0 to hold out indefinitely for a real overlap
     */
    record Find(java.util.List<Short> interestIds, int patience) implements ClientFrame {}

    record CancelFind() implements ClientFrame {}
}
