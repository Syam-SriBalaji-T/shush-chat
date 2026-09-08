package site.syamdev.shush.config;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.security.scram.ScramLoginModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.HashMap;
import java.util.Map;

/**
 * Adds SASL/SCRAM to the Kafka clients, but only when a username is configured.
 *
 * <p>Conditional on purpose. The broker in the compose stack requires authentication; the
 * Testcontainers broker the test suite runs against does not, and forcing SASL there would mean
 * either standing up SCRAM users in every test or turning the credentials off with a test
 * profile that no longer resembles production. Keying it on "is a username set" lets one
 * configuration serve both without either pretending to be the other.
 *
 * <p>SASL_PLAINTEXT rather than SASL_SSL: authentication without transport encryption. Inside a
 * Docker network that is a reasonable trade; across a real network it is not, and a deployment
 * would use SASL_SSL with the same credentials.
 */
@Configuration
class KafkaAuthConfig {

    private final Map<String, Object> saslProperties;

    KafkaAuthConfig(@Value("${shush.kafka-auth.username:}") String username,
                    @Value("${shush.kafka-auth.password:}") String password) {
        // A blank username means no authentication. Checked here rather than with
        // @ConditionalOnProperty because that treats an empty string as "present" and would
        // switch SASL on for a property that is deliberately empty.
        this.saslProperties = username == null || username.isBlank()
                ? Map.of()
                : Map.of(
                        CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT",
                        SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-256",
                        // Built here rather than written into application.yml, so the password
                        // never has to survive YAML quoting or appear in a config dump.
                        SaslConfigs.SASL_JAAS_CONFIG, "%s required username=\"%s\" password=\"%s\";"
                                .formatted(ScramLoginModule.class.getName(), username, password));
    }

    @Bean
    DefaultKafkaProducerFactoryCustomizer producerSasl() {
        return factory -> apply(factory::updateConfigs);
    }

    @Bean
    DefaultKafkaConsumerFactoryCustomizer consumerSasl() {
        return factory -> apply(factory::updateConfigs);
    }

    /**
     * The admin client needs the credentials too, and there is no customizer hook for it, so
     * this replaces Boot's {@code KafkaAdmin} outright.
     *
     * <p>Leaving it out was silent and expensive. The admin client is what applies the
     * {@code NewTopic} bean, so without credentials it could not authenticate, could not create
     * the topic, and said so only as repeated "Node -1 disconnected" at INFO -- it does not fail
     * startup. The broker then auto-created the topic on first produce with its own default of
     * <em>one</em> partition. Everything worked, and the ordering guarantee still held, but on a
     * single partition: eleven of the twelve consumers idle, and the partitioning this whole
     * design rests on quietly not happening. It was only visible in "partitions assigned: []".
     */
    @Bean
    KafkaAdmin kafkaAdmin(KafkaProperties properties,
                          ObjectProvider<KafkaConnectionDetails> connectionDetails,
                          ObjectProvider<SslBundles> sslBundles) {
        Map<String, Object> config =
                new HashMap<>(properties.buildAdminProperties(sslBundles.getIfAvailable()));
        // Replacing Boot's bean means also doing what Boot's bean does. Under
        // @ServiceConnection the broker address comes from KafkaConnectionDetails, not from
        // spring.kafka.bootstrap-servers, and building this from the properties alone points
        // the admin client at localhost while producer and consumer talk to the container --
        // which does not fail, it just stops the topic from being created where it is needed.
        connectionDetails.ifAvailable(details -> config.put(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, details.getAdminBootstrapServers()));
        config.putAll(saslProperties);
        KafkaAdmin admin = new KafkaAdmin(config);
        admin.setFatalIfBrokerNotAvailable(properties.getAdmin().isFailFast());
        admin.setModifyTopicConfigs(properties.getAdmin().isModifyTopicConfigs());
        admin.setAutoCreate(properties.getAdmin().isAutoCreate());
        return admin;
    }

    private void apply(java.util.function.Consumer<Map<String, Object>> update) {
        if (!saslProperties.isEmpty()) {
            update.accept(saslProperties);
        }
    }
}
