package site.syamdev.shush.social;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a bug that only showed up about half the time.
 *
 * <p>{@code Friendship} stores the pair in a fixed order so the primary key can enforce one row
 * per pair, and the database asserts that order with a check constraint. Java's
 * {@code UUID.compareTo} compares the halves as <em>signed</em> longs while Postgres compares
 * the bytes unsigned, so for any pair differing in the top bit the two disagree and the insert
 * is rejected. With random ids that fails on roughly half of all pairs, at random.
 */
class FriendshipTest {

    /** Differ in the top bit, which is exactly where the two orderings disagree. */
    private static final UUID LOW = UUID.fromString("00000000-0000-4000-8000-000000000000");
    private static final UUID HIGH = UUID.fromString("f0000000-0000-4000-8000-000000000000");

    @Test
    void javaAndPostgresDisagreeAboutThisPair() {
        assertThat(LOW.compareTo(HIGH))
                .as("Java's signed comparison calls the f0... id the smaller one")
                .isPositive();
    }

    @Test
    void theStoredOrderMatchesTheDatabasesOwnOrdering() {
        Friendship friendship = Friendship.between(HIGH, LOW, UUID.randomUUID(), Instant.now());

        assertThat(friendship.getUserAId()).isEqualTo(LOW);
        assertThat(friendship.getUserBId()).isEqualTo(HIGH);
    }

    @Test
    void theOrderIsTheSameWhicheverWayRoundTheArgumentsCome() {
        Instant now = Instant.now();
        Friendship oneWay = Friendship.between(LOW, HIGH, UUID.randomUUID(), now);
        Friendship theOther = Friendship.between(HIGH, LOW, UUID.randomUUID(), now);

        assertThat(oneWay.getUserAId()).isEqualTo(theOther.getUserAId());
        assertThat(oneWay.getUserBId()).isEqualTo(theOther.getUserBId());
    }

    @Test
    void everyRandomPairIsOrderedTheWayTheCheckConstraintRequires() {
        for (int i = 0; i < 1_000; i++) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            Friendship friendship = Friendship.between(first, second, UUID.randomUUID(), Instant.now());

            // Byte-order comparison, which is what "user_a_id < user_b_id" means in Postgres.
            assertThat(asBytes(friendship.getUserAId()))
                    .isLessThan(asBytes(friendship.getUserBId()));
        }
    }

    @Test
    void eitherSideCanFindTheOther() {
        Friendship friendship = Friendship.between(LOW, HIGH, UUID.randomUUID(), Instant.now());

        assertThat(friendship.otherThan(LOW)).isEqualTo(HIGH);
        assertThat(friendship.otherThan(HIGH)).isEqualTo(LOW);
    }

    private static String asBytes(UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
