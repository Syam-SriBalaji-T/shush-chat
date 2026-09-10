package site.syamdev.shush.message;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MessageHideRepository extends JpaRepository<MessageHide, MessageReaction.Key> {

    List<MessageHide> findByUserIdAndMessageIdIn(UUID userId, Collection<UUID> messageIds);

    boolean existsByMessageIdAndUserId(UUID messageId, UUID userId);
}
