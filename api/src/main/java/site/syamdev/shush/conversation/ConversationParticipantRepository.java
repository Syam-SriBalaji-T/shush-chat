package site.syamdev.shush.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ConversationParticipantRepository
        extends JpaRepository<ConversationParticipant, ConversationParticipant.Key> {

    List<ConversationParticipant> findByConversationId(UUID conversationId);

    boolean existsByConversationIdAndUserId(UUID conversationId, UUID userId);

    @Modifying
    @Query("""
            update ConversationParticipant p
            set p.unreadCount = p.unreadCount + 1
            where p.conversationId = :conversationId and p.userId <> :senderId
            """)
    int incrementUnreadForRecipients(@Param("conversationId") UUID conversationId,
                                     @Param("senderId") UUID senderId);
}
