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
}
