package site.syamdev.shush.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

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
