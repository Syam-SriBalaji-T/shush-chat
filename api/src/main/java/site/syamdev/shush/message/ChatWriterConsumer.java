package site.syamdev.shush.message;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import site.syamdev.shush.common.ApiException;
import site.syamdev.shush.media.MediaService;
import site.syamdev.shush.realtime.MessageDispatcher;

/**
 * The only writer to {@code messages}.
 *
 * <p>Because {@code chat.messages} is keyed by conversation id, every message for one
 * conversation lands on one partition and is consumed here by exactly one thread. That is the
 * whole ordering argument: sequence numbers are handed out in the order the broker accepted
 * the messages, and no second writer exists to disagree.
 *
 * <p>Delivery from the broker is at-least-once -- a crash between the database commit and the
 * offset commit redelivers. The dedup constraint is what turns that into
 * effectively-exactly-once.
 */
@Component
class ChatWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatWriterConsumer.class);

    private final MessageService messages;
    private final MessageDispatcher dispatcher;
    private final MediaService media;
    private final Counter persisted;
    private final Counter deduplicated;

    ChatWriterConsumer(MessageService messages, MessageDispatcher dispatcher, MediaService media,
                       MeterRegistry meters) {
        this.messages = messages;
        this.dispatcher = dispatcher;
        this.media = media;
        this.persisted = Counter.builder("shush.messages.persisted")
                .description("chat messages committed to postgres")
                .register(meters);
        this.deduplicated = Counter.builder("shush.messages.deduplicated")
                .description("redelivered or client-retried messages rejected by the dedup constraint")
                .register(meters);
    }

    @KafkaListener(topics = "${shush.kafka.chat-topic}", groupId = "${shush.kafka.consumer-group}")
    void onMessage(ChatMessageEvent event) {
        // Ask storage, not the client. A message referencing a key whose bytes were never
        // uploaded would reach every recipient as a picture that is not there, and the check
        // belongs here rather than at the socket because this is the last point before the
        // message becomes part of the conversation's permanent order.
        if (event.mediaKey() != null
                && !media.confirm(event.mediaKey(), event.conversationId(), event.senderId())) {
            log.warn("dropping a message for conversation {} whose media was never uploaded",
                    event.conversationId());
            return;
        }

        MessageService.Append append;
        try {
            append = messages.append(event.conversationId(), event.senderId(), event.clientMsgId(),
                    Message.Kind.fromWire(event.kind()), event.body(), event.mediaKey());
        } catch (ApiException rejected) {
            // A message whose conversation is gone, or whose body the writer refuses, can never
            // succeed on redelivery. Retrying it would stall its partition -- and with it every
            // other conversation that hashes there -- forever. Never log the body.
            log.warn("dropping an unwritable message for conversation {}: {}",
                    event.conversationId(), rejected.getCode());
            return;
        }

        if (append.duplicate()) {
            deduplicated.increment();
            // Already persisted and already delivered. Delivering it again is precisely the
            // duplicate the constraint exists to stop -- but the sender still gets its ack,
            // because a retry usually means it never saw the first one.
            dispatcher.acknowledge(event.senderId(), append.message(), true);
            return;
        }

        persisted.increment();
        dispatcher.acknowledge(event.senderId(), append.message(), false);
        dispatcher.deliver(append.message());
    }
}
