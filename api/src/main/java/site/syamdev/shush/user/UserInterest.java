package site.syamdev.shush.user;

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
@Table(name = "user_interests")
@IdClass(UserInterest.Key.class)
public class UserInterest {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "interest_id", nullable = false)
    private Short interestId;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    protected UserInterest() {
    }

    public UserInterest(UUID userId, Short interestId, Instant lastUsedAt) {
        this.userId = userId;
        this.interestId = interestId;
        this.lastUsedAt = lastUsedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public Short getInterestId() {
        return interestId;
    }

    /** JPA requires a no-arg constructor here, so this cannot be a record. */
    public static class Key implements Serializable {

        private UUID userId;
        private Short interestId;

        public Key() {
        }

        public Key(UUID userId, Short interestId) {
            this.userId = userId;
            this.interestId = interestId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(userId, other.userId)
                    && Objects.equals(interestId, other.interestId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, interestId);
        }
    }
}
