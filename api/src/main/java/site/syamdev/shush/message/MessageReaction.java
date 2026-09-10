package site.syamdev.shush.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One person's reaction to one message. The primary key is what limits it to one. */
@Entity
@Table(name = "message_reactions")
@IdClass(MessageReaction.Key.class)
public class MessageReaction {

    @Id
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "emoji", nullable = false, columnDefinition = "text")
    private String emoji;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MessageReaction() {
    }

    public MessageReaction(UUID messageId, UUID userId, String emoji, Instant createdAt) {
        this.messageId = messageId;
        this.userId = userId;
        this.emoji = emoji;
        this.createdAt = createdAt;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmoji() {
        return emoji;
    }

    public void changeTo(String emoji, Instant when) {
        this.emoji = emoji;
        this.createdAt = when;
    }

    public record Key(UUID messageId, UUID userId) implements Serializable {

        public Key() {
            this(null, null);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(messageId, key.messageId)
                    && Objects.equals(userId, key.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(messageId, userId);
        }
    }
}
