package site.syamdev.shush.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    private UUID id;

    @Column(name = "kind", nullable = false, columnDefinition = "text")
    private String kind;

    @Column(name = "state", nullable = false, columnDefinition = "text")
    private String state;

    /**
     * The interest ids this pair were matched on, or null when the patience window ran out and
     * they were matched at random. The null is meaningful, not missing data.
     */
    @Column(name = "matched_on", columnDefinition = "smallint[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private int[] matchedOn;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "purge_after")
    private Instant purgeAfter;

    protected Conversation() {
    }

    public Conversation(UUID id, Kind kind, Instant now) {
        this.id = id;
        this.kind = kind.wireValue();
        this.state = State.ACTIVE.wireValue();
        this.lastSeq = 0;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Kind getKind() {
        return Kind.fromWire(kind);
    }

    public State getState() {
        return State.fromWire(state);
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Instant getPurgeAfter() {
        return purgeAfter;
    }

    public int[] getMatchedOn() {
        return matchedOn == null ? null : matchedOn.clone();
    }

    public boolean wasRandomMatch() {
        return matchedOn == null;
    }

    void matchedOn(int[] interestIds) {
        this.matchedOn = interestIds;
    }

    void scheduleForPurge(Instant purgeAfter) {
        this.purgeAfter = purgeAfter;
    }

    void keep() {
        this.state = State.KEPT.wireValue();
        this.kind = Kind.FRIEND.wireValue();
        this.purgeAfter = null;
    }

    void end(Instant endedAt, Instant purgeAfter) {
        this.state = State.ENDED.wireValue();
        this.endedAt = endedAt;
        this.purgeAfter = purgeAfter;
    }

    public enum Kind {
        STRANGER, FRIEND;

        public String wireValue() {
            return name().toLowerCase();
        }

        public static Kind fromWire(String value) {
            return valueOf(value.toUpperCase());
        }
    }

    public enum State {
        ACTIVE, ENDED, KEPT;

        public String wireValue() {
            return name().toLowerCase();
        }

        public static State fromWire(String value) {
            return valueOf(value.toUpperCase());
        }
    }
}
