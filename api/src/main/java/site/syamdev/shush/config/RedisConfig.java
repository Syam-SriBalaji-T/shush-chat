package site.syamdev.shush.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.Executors;

@Configuration
class RedisConfig {

    /**
     * The backplane's subscriber side.
     */
    @Bean
    RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Deliver on the receiving thread rather than handing each message to a pool. The
        // default pool destroys per-channel order -- two frames for one conversation race each
        // other to the socket -- and order is the entire claim. The listener only enqueues onto
        // a per-user chain, so this thread is never held for long.
        container.setTaskExecutor(Runnable::run);

        container.setSubscriptionExecutor(Executors.newVirtualThreadPerTaskExecutor());
        return container;
    }
}
