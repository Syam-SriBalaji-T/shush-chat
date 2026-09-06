package site.syamdev.shush.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row per pair, never two.
 *
 * <p>The ids are stored in a fixed order so the primary key can enforce that. Storing both
 * directions would make "are these two friends?" a question with two answers that can disagree,
 * and eventually they do.
 */
@Entity
@Table(name = "friendships")
@IdClass(Friendship.Key.class)
public class Friendship {

    @Id
    @Column(name = "user_a_id", nullable = false)
    private UUID userAId;

    @Id
    @Column(name = "user_b_id", nullable = false)
    private UUID userBId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Friendship() {
    }

    public static Friendship between(UUID first, UUID second, UUID conversationId, Instant now) {
        Friendship friendship = new Friendship();
        friendship.userAId = lower(first, second);
        friendship.userBId = higher(first, second);
        friendship.conversationId = conversationId;
        friendship.createdAt = now;
        return friendship;
    }

    public UUID getUserAId() {
        return userAId;
    }

    public UUID getUserBId() {
        return userBId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID otherThan(UUID userId) {
        return userAId.equals(userId) ? userBId : userAId;
    }

    static UUID lower(UUID first, UUID second) {
        return compareAsPostgresDoes(first, second) <= 0 ? first : second;
    }

    static UUID higher(UUID first, UUID second) {
        return compareAsPostgresDoes(first, second) <= 0 ? second : first;
    }

    /**
     * Orders two UUIDs the way Postgres does, which is <em>not</em> the way
     * {@link UUID#compareTo} does.
     *
     * <p>Java compares the two halves as signed longs; Postgres compares the sixteen bytes
     * unsigned. For any pair differing in the top bit the two disagree, so ordering a pair in
     * Java and then asserting {@code user_a_id < user_b_id} in a check constraint rejects
     * roughly half of all pairs -- and does it non-deterministically, because the ids are
     * random. The constraint is the authority, so the ordering has to match it.
     */
    private static int compareAsPostgresDoes(UUID first, UUID second) {
        int high = Long.compareUnsigned(first.getMostSignificantBits(), second.getMostSignificantBits());
        return high != 0
                ? high
                : Long.compareUnsigned(first.getLeastSignificantBits(), second.getLeastSignificantBits());
    }

    /** JPA requires a no-arg constructor here, so this cannot be a record. */
    public static class Key implements Serializable {

        private UUID userAId;
        private UUID userBId;

        public Key() {
        }

        public Key(UUID userAId, UUID userBId) {
            this.userAId = userAId;
            this.userBId = userBId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(userAId, other.userAId)
                    && Objects.equals(userBId, other.userBId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userAId, userBId);
        }
    }
}
