package site.syamdev.shush.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages")
public class Message {

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "kind", nullable = false, columnDefinition = "text")
    private String kind;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "media_key", columnDefinition = "text")
    private String mediaKey;

    @Column(name = "client_msg_id", nullable = false)
    private UUID clientMsgId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The seq this replies to, in the same conversation. Null when it replies to nothing. */
    @Column(name = "reply_to_seq")
    private Long replyToSeq;

    /**
     * Deleted for everyone. The row survives because seq is dense by design -- removing one
     * would leave a gap the ordering harness reads as a lost message -- but the body does not.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Message() {
    }

    public Message(UUID id, UUID conversationId, UUID senderId, long seq, Kind kind,
                   String body, String mediaKey, UUID clientMsgId, Instant createdAt) {
        this(id, conversationId, senderId, seq, kind, body, mediaKey, clientMsgId, createdAt, null);
    }

    public Message(UUID id, UUID conversationId, UUID senderId, long seq, Kind kind,
                   String body, String mediaKey, UUID clientMsgId, Instant createdAt,
                   Long replyToSeq) {
        this.replyToSeq = replyToSeq;
        this.id = id;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.seq = seq;
        this.kind = kind.wireValue();
        this.body = body;
        this.mediaKey = mediaKey;
        this.clientMsgId = clientMsgId;
        this.createdAt = createdAt;
    }

    public Long getReplyToSeq() {
        return replyToSeq;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Keeps the row and the sequence, drops what was said. */
    public void deleteForEveryone(Instant when) {
        this.deletedAt = when;
        this.body = null;
        this.mediaKey = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public long getSeq() {
        return seq;
    }

    public Kind getKind() {
        return Kind.fromWire(kind);
    }

    public String getBody() {
        return body;
    }

    public String getMediaKey() {
        return mediaKey;
    }

    public UUID getClientMsgId() {
        return clientMsgId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public enum Kind {
        TEXT, IMAGE, SYSTEM;

        public String wireValue() {
            return name().toLowerCase();
        }

        public static Kind fromWire(String value) {
            return valueOf(value.toUpperCase());
        }
    }
}
