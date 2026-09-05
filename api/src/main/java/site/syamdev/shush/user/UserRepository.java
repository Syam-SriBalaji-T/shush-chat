package site.syamdev.shush.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByDisplayName(String displayName);

    Optional<User> findByDisplayName(String displayName);
}
