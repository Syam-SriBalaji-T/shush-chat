package site.syamdev.shush.realtime;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

/**
 * Everything a client may send. Sealed so the handler's switch is exhaustive at compile time --
 * adding a frame type in a later phase will not silently fall through to "unknown".
 *
 * <p>JSON is camelCase (see CLAUDE.md); Postgres stays snake_case. Mapping happens at the edge.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientFrame.Send.class, name = "send")
})
public sealed interface ClientFrame {

    /**
     * @param clientMsgId the idempotency key, stable across the client's retries of one
     *                    logical send. The server never generates it.
     */
    record Send(UUID conversationId, UUID clientMsgId, String kind, String body, String mediaKey)
            implements ClientFrame {
    }
}
