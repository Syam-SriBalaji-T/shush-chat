package site.syamdev.shush.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The thing that decides whether a conversation survives.
 *
 * <p>A stranger conversation with no request is deleted; one with an accepted request is kept
 * forever. That is the whole retention model, and it is why this row matters more than its size
 * suggests (pre-plan.md 6).
 */
@Entity
@Table(name = "friend_requests")
public class FriendRequest {

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "from_user_id", nullable = false)
    private UUID fromUserId;

    @Column(name = "to_user_id", nullable = false)
    private UUID toUserId;

    @Column(name = "status", nullable = false, columnDefinition = "text")
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected FriendRequest() {
    }

    public FriendRequest(UUID id, UUID conversationId, UUID fromUserId, UUID toUserId,
                         Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.status = Status.PENDING.wireValue();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getFromUserId() {
        return fromUserId;
    }

    public UUID getToUserId() {
        return toUserId;
    }

    public Status getStatus() {
        return Status.fromWire(status);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    void accept() {
        this.status = Status.ACCEPTED.wireValue();
    }

    /**
     * Kept rather than deleted, so the unique constraint on (conversation, sender) stops the
     * same request being sent again. Nothing is announced -- you simply never hear back
     * (pre-plan.md 6).
     */
    void decline() {
        this.status = Status.DECLINED.wireValue();
    }

    void expire() {
        this.status = Status.EXPIRED.wireValue();
    }

    public enum Status {
        PENDING, ACCEPTED, DECLINED, EXPIRED;

        public String wireValue() {
            return name().toLowerCase();
        }

        public static Status fromWire(String value) {
            return valueOf(value.toUpperCase());
        }
    }
}
