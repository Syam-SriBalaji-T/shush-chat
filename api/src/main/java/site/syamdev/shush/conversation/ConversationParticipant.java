package site.syamdev.shush.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "conversation_participants")
@IdClass(ConversationParticipant.Key.class)
public class ConversationParticipant {

    @Id
    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "read_cursor_seq", nullable = false)
    private long readCursorSeq;

    @Column(name = "unread_count", nullable = false)
    private int unreadCount;

    @Column(name = "left_at")
    private Instant leftAt;

    protected ConversationParticipant() {
    }

    public ConversationParticipant(UUID conversationId, UUID userId) {
        this.conversationId = conversationId;
        this.userId = userId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public long getReadCursorSeq() {
        return readCursorSeq;
    }

    public int getUnreadCount() {
        return unreadCount;
    }

    public Instant getLeftAt() {
        return leftAt;
    }

    /** JPA requires a no-arg constructor here, so this cannot be a record. */
    public static class Key implements Serializable {

        private UUID conversationId;
        private UUID userId;

        public Key() {
        }

        public Key(UUID conversationId, UUID userId) {
            this.conversationId = conversationId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(conversationId, other.conversationId)
                    && Objects.equals(userId, other.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(conversationId, userId);
        }
    }
}
