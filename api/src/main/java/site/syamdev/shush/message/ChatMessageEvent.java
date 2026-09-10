package site.syamdev.shush.message;

import java.time.Instant;
import java.util.UUID;

/**
 * What is written to {@code chat.messages}. The topic is the write-ahead log for chat, so this
 * is the durable record of intent -- everything needed to persist the message without
 * consulting the node that produced it.
 *
 * @param conversationId also the partition key. One conversation therefore lands on one
 *                       partition, handled by one consumer, in one order. Never key by
 *                       anything else.
 */
public record ChatMessageEvent(UUID conversationId,
                               UUID senderId,
                               UUID clientMsgId,
                               String kind,
                               String body,
                               String mediaKey,
                               Long replyToSeq,
                               Instant producedAt) {
}
