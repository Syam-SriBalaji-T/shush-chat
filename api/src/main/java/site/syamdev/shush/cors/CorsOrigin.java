package site.syamdev.shush.cors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One browser origin permitted to call this API. See {@code V6__cors_origins.sql}. */
@Entity
@Table(name = "cors_origins")
public class CorsOrigin {

    @Id
    @Column(name = "origin", nullable = false, columnDefinition = "text")
    private String origin;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CorsOrigin() {
    }

    public CorsOrigin(String origin, String note, Instant createdAt) {
        this.origin = origin;
        this.note = note;
        this.createdAt = createdAt;
    }

    public String getOrigin() {
        return origin;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
