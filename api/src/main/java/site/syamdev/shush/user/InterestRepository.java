package site.syamdev.shush.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface InterestRepository extends JpaRepository<Interest, Short> {

    List<Interest> findAllByOrderByPopularityDescIdAsc();

    /** The caller's own interests, most recently used first -- a returning visitor's default tiles. */
    @Query("""
            select i from Interest i
            join UserInterest ui on ui.interestId = i.id
            where ui.userId = :userId
            order by ui.lastUsedAt desc
            """)
    List<Interest> findLastUsedBy(UUID userId);
}
