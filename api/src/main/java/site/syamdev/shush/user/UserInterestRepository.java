package site.syamdev.shush.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserInterestRepository extends JpaRepository<UserInterest, UserInterest.Key> {

    List<UserInterest> findByUserId(UUID userId);
}
