package site.syamdev.shush.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The one legitimate need that name search would have served -- "come talk to me on Shush" --
 * without the one it would also have served, which is finding someone who left a conversation
 * to get away from you (pre-plan.md 6).
 */
@Entity
@Table(name = "invite_links")
public class InviteLink {

    @Id
    @Column(name = "code", columnDefinition = "text")
    private String code;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected InviteLink() {
    }

    public InviteLink(String code, UUID ownerId, Instant createdAt, Instant expiresAt) {
        this.code = code;
        this.ownerId = ownerId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getCode() {
        return code;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean hasExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
