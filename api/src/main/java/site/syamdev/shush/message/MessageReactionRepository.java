package site.syamdev.shush.message;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageReactionRepository
        extends JpaRepository<MessageReaction, MessageReaction.Key> {

    Optional<MessageReaction> findByMessageIdAndUserId(UUID messageId, UUID userId);

    /** One query for a page of history, rather than one per message. */
    List<MessageReaction> findByMessageIdIn(Collection<UUID> messageIds);

    void deleteByMessageIdAndUserId(UUID messageId, UUID userId);
}
