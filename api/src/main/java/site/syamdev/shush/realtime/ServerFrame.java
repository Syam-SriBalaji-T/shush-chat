package site.syamdev.shush.realtime;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import site.syamdev.shush.message.Message;

import java.time.Instant;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ServerFrame.Ack.class, name = "ack"),
        @JsonSubTypes.Type(value = ServerFrame.MessageFrame.class, name = "message"),
        @JsonSubTypes.Type(value = ServerFrame.Error.class, name = "error")
})
public sealed interface ServerFrame {

    /**
     * @param status {@code sent} once the message is durable and sequenced. Phase 2 splits this
     *               into {@code sent} on produce and {@code delivered} after persistence.
     */
    record Ack(UUID clientMsgId, String status, UUID messageId, long seq, boolean duplicate)
            implements ServerFrame {

        static Ack accepted(Message message, boolean duplicate) {
            return new Ack(message.getClientMsgId(), "sent", message.getId(), message.getSeq(), duplicate);
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
}
