package site.syamdev.shush.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import site.syamdev.shush.cors.OriginHandshakeInterceptor;
import site.syamdev.shush.realtime.ChatWebSocketHandler;
import site.syamdev.shush.realtime.JwtHandshakeInterceptor;

@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final OriginHandshakeInterceptor originHandshakeInterceptor;

    WebSocketConfig(ChatWebSocketHandler chatHandler,
                    JwtHandshakeInterceptor jwtHandshakeInterceptor,
                    OriginHandshakeInterceptor originHandshakeInterceptor) {
        this.chatHandler = chatHandler;
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
        this.originHandshakeInterceptor = originHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatHandler, "/ws/chat")
                // Origin first: no point authenticating a socket that is not allowed to exist.
                .addInterceptors(originHandshakeInterceptor, jwtHandshakeInterceptor)
                // Left open here on purpose. This list is fixed at registration, and the whole
                // point of cors_origins is that adding a frontend does not need a restart --
                // so the actual decision is OriginHandshakeInterceptor's, per handshake.
                .setAllowedOriginPatterns("*");
    }
}
