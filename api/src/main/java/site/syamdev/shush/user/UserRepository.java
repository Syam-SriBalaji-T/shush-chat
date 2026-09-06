package site.syamdev.shush.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByDisplayName(String displayName);

    Optional<User> findByDisplayName(String displayName);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /**
     * Claims a display name if it is still free.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than catching the unique violation: in Postgres a
     * raised constraint error aborts the whole transaction, so a caught-and-retried insert can
     * never succeed -- every following statement fails with "current transaction is aborted".
     * Letting the database swallow the conflict keeps the transaction usable, which is what
     * makes retrying possible at all.
     *
     * @return 1 if this call took the name, 0 if another allocation got there first
     */
    @Modifying
    @Query(value = """
            insert into users (id, display_name, is_anonymous, created_at, last_seen_at)
            values (:id, :displayName, true, :now, :now)
            on conflict (display_name) do nothing
            """, nativeQuery = true)
    int insertIfNameFree(@Param("id") UUID id,
                         @Param("displayName") String displayName,
                         @Param("now") Instant now);

    /**
     * Renames only if the name is free. Same reasoning as the insert: the unique index decides,
     * and a raised violation would abort the transaction rather than let the caller try again.
     */
    @Modifying
    @Query(value = """
            update users set display_name = :displayName
            where id = :id
              and not exists (select 1 from users taken where taken.display_name = :displayName)
            """, nativeQuery = true)
    int claimDisplayName(@Param("id") UUID id, @Param("displayName") String displayName);
}
