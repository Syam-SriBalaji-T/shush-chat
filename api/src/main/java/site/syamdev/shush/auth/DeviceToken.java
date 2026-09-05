package site.syamdev.shush.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The browser's half of an anonymous identity. Only the SHA-256 of the token is stored, so a
 * database leak does not hand anyone a working login -- the raw token exists solely in the
 * client's localStorage (pre-plan.md 8.1).
 */
@Entity
@Table(name = "device_tokens")
public class DeviceToken {

    @Id
    @Column(name = "token_hash", columnDefinition = "text")
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    protected DeviceToken() {
    }

    public DeviceToken(String tokenHash, UUID userId, Instant now) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.createdAt = now;
        this.lastUsedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void markUsed(Instant now) {
        this.lastUsedAt = now;
    }
}
