package site.syamdev.shush.message;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The only way a message enters the system. Controllers and socket handlers produce here and
 * never touch {@code messages} -- writing to Postgres and producing to Redpanda from the same
 * request would be a dual write that cannot be made atomic without an outbox.
 */
@Component
public class ChatMessageProducer {

    private final KafkaTemplate<String, ChatMessageEvent> kafka;
    private final String topic;
    private final Clock clock;
    private final Counter produced;

    ChatMessageProducer(KafkaTemplate<String, ChatMessageEvent> kafka,
                        @Value("${shush.kafka.chat-topic}") String topic,
                        Clock clock,
                        MeterRegistry meters) {
        this.kafka = kafka;
        this.topic = topic;
        this.clock = clock;
        this.produced = Counter.builder("shush.messages.produced")
                .description("chat messages written to the log")
                .register(meters);
    }

    public CompletableFuture<Void> produce(UUID conversationId, UUID senderId, UUID clientMsgId,
                                           Message.Kind kind, String body, String mediaKey) {
        ChatMessageEvent event = new ChatMessageEvent(conversationId, senderId, clientMsgId,
                kind.wireValue(), body, mediaKey, clock.instant());

        // The key is the conversation id and nothing else. This single line is where the
        // ordering guarantee comes from; everything downstream only has to avoid losing it.
        return kafka.send(topic, conversationId.toString(), event)
                .thenAccept(result -> produced.increment());
    }
}
