package site.syamdev.shush.message;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    Optional<Message> findByConversationIdAndSenderIdAndClientMsgId(UUID conversationId,
                                                                   UUID senderId,
                                                                   UUID clientMsgId);

    /** Newest-first page for history. The service reverses it; the cursor walks backwards. */
    List<Message> findByConversationIdAndSeqLessThanOrderBySeqDesc(UUID conversationId,
                                                                   long beforeSeq,
                                                                   Limit limit);

    /** The resume path: everything a returning client has not seen, oldest first. */
    List<Message> findByConversationIdAndSeqGreaterThanOrderBySeqAsc(UUID conversationId,
                                                                     long afterSeq,
                                                                     Limit limit);

    List<Message> findByConversationIdOrderBySeqAsc(UUID conversationId);

    long countByConversationId(UUID conversationId);

    /**
     * Dedup is the {@code messages_conversation_sender_client_msg_id_key} constraint. Letting
     * Postgres swallow the conflict, rather than catching the violation, keeps the transaction
     * usable -- a raised constraint error would abort it and the caller could read nothing back.
     */
    @Modifying
    @Query(value = """
            insert into messages (id, conversation_id, sender_id, seq, kind, body, media_key,
                                  client_msg_id, created_at, reply_to_seq)
            values (:id, :conversationId, :senderId, :seq, :kind, :body, :mediaKey,
                    :clientMsgId, :createdAt, :replyToSeq)
            on conflict (conversation_id, sender_id, client_msg_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("conversationId") UUID conversationId,
                       @Param("senderId") UUID senderId,
                       @Param("seq") long seq,
                       @Param("kind") String kind,
                       @Param("body") String body,
                       @Param("mediaKey") String mediaKey,
                       @Param("clientMsgId") UUID clientMsgId,
                       @Param("createdAt") Instant createdAt,
                       @Param("replyToSeq") Long replyToSeq);
}
