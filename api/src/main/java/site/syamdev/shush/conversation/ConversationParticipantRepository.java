package site.syamdev.shush.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationParticipantRepository
        extends JpaRepository<ConversationParticipant, ConversationParticipant.Key> {

    List<ConversationParticipant> findByConversationId(UUID conversationId);

    boolean existsByConversationIdAndUserId(UUID conversationId, UUID userId);

    Optional<ConversationParticipant> findByConversationIdAndUserId(UUID conversationId, UUID userId);

    @Modifying
    @Query("""
            update ConversationParticipant p
            set p.unreadCount = p.unreadCount + 1
            where p.conversationId = :conversationId and p.userId <> :senderId
            """)
    int incrementUnreadForRecipients(@Param("conversationId") UUID conversationId,
                                     @Param("senderId") UUID senderId);

    /**
     * The read cursor only ever moves forward. Two devices reading the same conversation at
     * once would otherwise let the slower one drag it backwards and resurrect read messages.
     */
    @Modifying
    @Query("""
            update ConversationParticipant p
            set p.readCursorSeq = :seq, p.unreadCount = 0
            where p.conversationId = :conversationId and p.userId = :userId and p.readCursorSeq < :seq
            """)
    int advanceReadCursor(@Param("conversationId") UUID conversationId,
                          @Param("userId") UUID userId,
                          @Param("seq") long seq);

    @Modifying
    @Query("""
            update ConversationParticipant p
            set p.leftAt = :leftAt
            where p.conversationId = :conversationId and p.userId = :userId and p.leftAt is null
            """)
    int markLeft(@Param("conversationId") UUID conversationId,
                 @Param("userId") UUID userId,
                 @Param("leftAt") Instant leftAt);

    /**
     * The other person in each of this user's still-open conversations. Used to tell exactly
     * the people who would notice that someone came online or went away.
     */
    @Query("""
            select p2.conversationId as conversationId, p2.userId as userId
            from ConversationParticipant p1
            join Conversation c on c.id = p1.conversationId
            join ConversationParticipant p2 on p2.conversationId = c.id
            where p1.userId = :userId
              and p2.userId <> :userId
              and c.state = 'active'
              and p1.leftAt is null
            """)
    List<CounterpartRow> findActiveCounterparts(@Param("userId") UUID userId);

    interface CounterpartRow {
        UUID getConversationId();

        UUID getUserId();
    }
}
