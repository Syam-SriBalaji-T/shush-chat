package site.syamdev.shush.spike;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Phase 0 spike only. Proves Flyway owns the schema and Hibernate merely validates it. */
@Entity
@Table(name = "spike_records")
class SpikeRecord {

    @Id
    private UUID id;

    @Column(name = "note", nullable = false, columnDefinition = "text")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SpikeRecord() {
    }

    SpikeRecord(UUID id, String note, Instant createdAt) {
        this.id = id;
        this.note = note;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    String getNote() {
        return note;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
