package site.syamdev.shush.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

@Configuration
class KafkaConfig {

    /**
     * Twelve partitions, replication 1 (one broker locally). The partition count is the
     * ceiling on consumer parallelism for this topic, and raising it later re-keys existing
     * conversations onto different partitions -- so it is set once, generously, up front.
     *
     * <p>Producer idempotence, acks and consumer isolation live in {@code application.yml};
     * Spring Boot's Kafka auto-configuration reads them from there.
     */
    /**
     * Never give up on a write.
     *
     * <p>The default handler retries ten times and then <em>skips the record</em>, which is
     * silent message loss: a database hiccup lasting a few seconds would quietly drop
     * everything in flight while every log line still said the system was healthy. For a
     * service whose central claim is that nothing is lost, that default is the wrong trade.
     *
     * <p>Retrying forever instead stalls the partition -- and with it the conversations that
     * hash to it -- until the write succeeds. That is the deliberate choice: one twelfth of
     * conversations pausing is recoverable and visible, a lost message is neither. If the stall
     * outlasts {@code max.poll.interval.ms} the consumer is evicted, the partition moves to
     * another replica, and the record is redelivered there from the uncommitted offset.
     *
     * <p>Records that can never succeed do not reach here: a message for a conversation that no
     * longer exists is dropped by the listener itself, and a record that cannot be deserialised
     * is classified as fatal by this handler rather than retried.
     */
    @Bean
    CommonErrorHandler chatWriterErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }

    @Bean
    NewTopic chatMessagesTopic(@Value("${shush.kafka.chat-topic}") String topic,
                               @Value("${shush.kafka.partitions}") int partitions) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(1)
                .config("retention.ms", Long.toString(Duration.ofDays(7).toMillis()))
                .build();
    }
}
