package site.syamdev.shush.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import site.syamdev.shush.realtime.ChatWebSocketHandler;
import site.syamdev.shush.realtime.JwtHandshakeInterceptor;

@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    WebSocketConfig(ChatWebSocketHandler chatHandler, JwtHandshakeInterceptor jwtHandshakeInterceptor) {
        this.chatHandler = chatHandler;
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatHandler, "/ws/chat")
                .addInterceptors(jwtHandshakeInterceptor)
                // The test client is served from a file:// page and, once deployed, from a
                // different origin to the API. Tightened in Phase 7 when that origin is known.
                .setAllowedOriginPatterns("*");
    }
}
