package site.syamdev.shush.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /**
     * Claims the next sequence number for a conversation. The row lock this takes is what
     * serialises concurrent senders, so {@code seq} is dense and gap-free -- and it is why
     * the bump and the message insert must share one transaction.
     */
    @Query(value = "update conversations set last_seq = last_seq + 1 where id = :id returning last_seq",
            nativeQuery = true)
    Long claimNextSeq(@Param("id") UUID conversationId);
}
