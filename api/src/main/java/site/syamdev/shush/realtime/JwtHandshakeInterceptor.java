package site.syamdev.shush.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import site.syamdev.shush.auth.JwtService;

import java.util.Map;
import java.util.UUID;

/**
 * Authenticates the socket before it opens. The browser WebSocket API cannot set an
 * Authorization header, so the JWT travels in the query string (plan.md 3.8) -- which is also
 * why this cannot be left to the resource-server filter chain.
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    static final String USER_ID_ATTRIBUTE = "shush.userId";

    private static final Logger log = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);

    private final JwtService jwtService;

    JwtHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("token");
        if (token == null || token.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            UUID userId = jwtService.verifyAndExtractUserId(token);
            attributes.put(USER_ID_ATTRIBUTE, userId);
            return true;
        } catch (RuntimeException e) {
            // Never log the token itself.
            log.debug("rejecting a websocket handshake with an invalid token");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
    }
}
