package site.syamdev.shush.social;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FriendshipRepository extends JpaRepository<Friendship, Friendship.Key> {

    @Query("select f from Friendship f where f.userAId = :userId or f.userBId = :userId")
    List<Friendship> findAllInvolving(@Param("userId") UUID userId);

    @Query("""
            select count(f) > 0 from Friendship f
            where (f.userAId = :first and f.userBId = :second)
               or (f.userAId = :second and f.userBId = :first)
            """)
    boolean existsBetween(@Param("first") UUID first, @Param("second") UUID second);

    /**
     * Either way round, because the pair is stored in one canonical order and the caller does
     * not know which of the two it is.
     */
    @Modifying
    @Query("""
            delete from Friendship f
            where (f.userAId = :first and f.userBId = :second)
               or (f.userAId = :second and f.userBId = :first)
            """)
    int deleteBetween(@Param("first") UUID first, @Param("second") UUID second);
}
