package site.syamdev.shush.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import site.syamdev.shush.spike.EchoWebSocketHandler;

@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private final EchoWebSocketHandler echoHandler;

    WebSocketConfig(EchoWebSocketHandler echoHandler) {
        this.echoHandler = echoHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(echoHandler, "/ws/echo").setAllowedOriginPatterns("*");
    }
}
