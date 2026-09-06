package site.syamdev.shush.social;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BlockRepository extends JpaRepository<Block, Block.Key> {

    @Query("select b from Block b where b.blockerId = :userId or b.blockedId = :userId")
    List<Block> findAllInvolving(@Param("userId") UUID userId);

    @Query("""
            select count(b) > 0 from Block b
            where (b.blockerId = :first and b.blockedId = :second)
               or (b.blockerId = :second and b.blockedId = :first)
            """)
    boolean existsBetween(@Param("first") UUID first, @Param("second") UUID second);
}
