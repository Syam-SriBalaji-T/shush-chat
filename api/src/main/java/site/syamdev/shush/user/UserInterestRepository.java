package site.syamdev.shush.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserInterestRepository extends JpaRepository<UserInterest, UserInterest.Key> {

    List<UserInterest> findByUserId(UUID userId);

    /**
     * Upsert rather than delete-then-insert.
     *
     * <p>The obvious implementation -- load the existing rows, bulk-delete them, save the new
     * ones -- is broken in a way that only shows up for a user who already has interests, which
     * is to say every returning visitor. A bulk delete bypasses the persistence context, so the
     * loaded entities stay managed, and saving rows with the same ids makes Hibernate try to
     * update rows the delete has already removed. Writing what should be there, and removing
     * only what should not, avoids the problem entirely and is idempotent under a retry.
     */
    @Modifying
    @Query(value = """
            insert into user_interests (user_id, interest_id, last_used_at)
            values (:userId, :interestId, :now)
            on conflict (user_id, interest_id) do update set last_used_at = :now
            """, nativeQuery = true)
    void select(@Param("userId") UUID userId,
                @Param("interestId") Short interestId,
                @Param("now") Instant now);

    @Modifying
    @Query("delete from UserInterest ui where ui.userId = :userId and ui.interestId not in :keep")
    void deselectOthers(@Param("userId") UUID userId, @Param("keep") Collection<Short> keep);

    @Modifying
    @Query("delete from UserInterest ui where ui.userId = :userId")
    void deselectAll(@Param("userId") UUID userId);
}
