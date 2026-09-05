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

/** Directional on purpose: blocking is not mutual, but its effect on matching is. */
@Entity
@Table(name = "blocks")
@IdClass(Block.Key.class)
public class Block {

    @Id
    @Column(name = "blocker_id", nullable = false)
    private UUID blockerId;

    @Id
    @Column(name = "blocked_id", nullable = false)
    private UUID blockedId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Block() {
    }

    public Block(UUID blockerId, UUID blockedId, Instant createdAt) {
        this.blockerId = blockerId;
        this.blockedId = blockedId;
        this.createdAt = createdAt;
    }

    public UUID getBlockerId() {
        return blockerId;
    }

    public UUID getBlockedId() {
        return blockedId;
    }

    /** JPA requires a no-arg constructor here, so this cannot be a record. */
    public static class Key implements Serializable {

        private UUID blockerId;
        private UUID blockedId;

        public Key() {
        }

        public Key(UUID blockerId, UUID blockedId) {
            this.blockerId = blockerId;
            this.blockedId = blockedId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(blockerId, other.blockerId)
                    && Objects.equals(blockedId, other.blockedId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(blockerId, blockedId);
        }
    }
}
