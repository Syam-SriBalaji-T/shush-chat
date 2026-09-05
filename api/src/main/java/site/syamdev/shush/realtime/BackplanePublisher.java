package site.syamdev.shush.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * Sends a frame to a user wherever they are connected -- or nowhere, if they are offline.
 *
 * <p>Everything the server originates goes through here: messages, acks, presence, typing and
 * read receipts alike. The caller never learns which node holds the recipient, and there is no
 * same-node shortcut, so a bug in delivery is a bug everywhere rather than only across nodes.
 */
@Component
public class BackplanePublisher {

    private static final Logger log = LoggerFactory.getLogger(BackplanePublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Counter published;

    BackplanePublisher(StringRedisTemplate redis, ObjectMapper json, MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.published = Counter.builder("shush.frames.published")
                .description("frames published to the backplane")
                .register(meters);
    }

    public void publish(UUID userId, ServerFrame frame) {
        String payload = write(frame);
        if (payload != null) {
            publishRaw(userId, payload);
        }
    }

    /** Serialises once for a fanout to several recipients. */
    public void publish(Collection<UUID> userIds, ServerFrame frame) {
        String payload = write(frame);
        if (payload == null) {
            return;
        }
        userIds.forEach(userId -> publishRaw(userId, payload));
    }

    private void publishRaw(UUID userId, String payload) {
        redis.convertAndSend(UserChannels.of(userId), payload);
        published.increment();
    }

    private String write(ServerFrame frame) {
        try {
            return json.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            log.error("could not serialise a {} frame", frame.getClass().getSimpleName(), e);
            return null;
        }
    }
}
