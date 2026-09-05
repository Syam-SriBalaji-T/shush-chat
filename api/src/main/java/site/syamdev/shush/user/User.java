package site.syamdev.shush.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per identity, anonymous or not. Signing up sets {@code email} and
 * {@code passwordHash} on this same row -- nothing is copied or migrated, which is why a
 * user's name, friends and history survive it unchanged (pre-plan.md 8.1).
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(name = "display_name", nullable = false, columnDefinition = "text")
    private String displayName;

    @Column(name = "is_anonymous", nullable = false)
    private boolean anonymous;

    @Column(name = "email", columnDefinition = "citext")
    private String email;

    @Column(name = "password_hash", columnDefinition = "text")
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected User() {
    }

    public User(UUID id, String displayName, Instant now) {
        this.id = id;
        this.displayName = displayName;
        this.anonymous = true;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isAnonymous() {
        return anonymous;
    }

    public String getEmail() {
        return email;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void touch(Instant now) {
        this.lastSeenAt = now;
    }

    /**
     * Attaches an email to the identity that already exists. Nothing is created, copied or
     * migrated -- the row keeps its id, its name, its friends and its conversations, which is
     * exactly why signing up resets nothing (pre-plan.md 8.1).
     */
    public void attachAccount(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.anonymous = false;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
