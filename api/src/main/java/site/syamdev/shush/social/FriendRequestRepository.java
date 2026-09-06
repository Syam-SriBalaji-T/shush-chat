package site.syamdev.shush.social;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FriendRequestRepository extends JpaRepository<FriendRequest, UUID> {

    Optional<FriendRequest> findByConversationIdAndFromUserId(UUID conversationId, UUID fromUserId);

    List<FriendRequest> findByConversationId(UUID conversationId);

    List<FriendRequest> findByToUserIdAndStatus(UUID toUserId, String status);

    List<FriendRequest> findByStatusAndExpiresAtBefore(String status, Instant before);

    boolean existsByConversationIdAndStatusIn(UUID conversationId, List<String> statuses);
}
