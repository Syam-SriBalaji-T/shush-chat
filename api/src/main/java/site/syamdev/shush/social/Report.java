package site.syamdev.shush.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Recorded, not acted upon. This runs with test users only; a real deployment of anonymous
 * strangers exchanging images would need actual moderation behind this, which is a separate
 * piece of work and not a feature toggle (pre-plan.md 10).
 */
@Entity
@Table(name = "reports")
public class Report {

    @Id
    private UUID id;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Column(name = "reported_id", nullable = false)
    private UUID reportedId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Report() {
    }

    public Report(UUID id, UUID reporterId, UUID reportedId, UUID conversationId,
                  String reason, Instant createdAt) {
        this.id = id;
        this.reporterId = reporterId;
        this.reportedId = reportedId;
        this.conversationId = conversationId;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }
}
