package site.syamdev.shush.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One message hidden from one person -- "delete for me".
 *
 * <p>Separate from {@code messages.deleted_at} because the two are different claims: one is
 * about the message, one is about a reader. Collapsing them would make "they deleted it" and
 * "I hid it" indistinguishable to everyone including the person who did it.
 */
@Entity
@Table(name = "message_hides")
@IdClass(MessageReaction.Key.class)
public class MessageHide {

    @Id
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MessageHide() {
    }

    public MessageHide(UUID messageId, UUID userId, Instant createdAt) {
        this.messageId = messageId;
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public UUID getUserId() {
        return userId;
    }
}
