package site.syamdev.shush.cors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Applies the {@code cors_origins} table to the WebSocket handshake.
 *
 * <p>The framework's own {@code setAllowedOriginPatterns} is fixed when handlers are
 * registered, which is exactly what the table exists to avoid — a new frontend would need a
 * restart to be able to open a socket even though its HTTP calls already worked. Registration
 * therefore stays permissive and the decision is made here, per handshake.
 *
 * <p>A request with no {@code Origin} header is allowed through. Origin is set by browsers;
 * the load harness, the integration tests and any server-side client send none, and refusing
 * those would be refusing the callers this check is not about.
 */
@Component
public class OriginHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OriginHandshakeInterceptor.class);

    private final AllowedOrigins allowed;

    OriginHandshakeInterceptor(AllowedOrigins allowed) {
        this.allowed = allowed;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String origin = request.getHeaders().getFirst(HttpHeaders.ORIGIN);
        if (origin == null) {
            return true;
        }
        // Same-origin: served through the one nginx, which is the normal path and needs no row.
        String host = request.getHeaders().getFirst(HttpHeaders.HOST);
        if (host != null && originMatchesHost(origin, host)) {
            return true;
        }
        if (allowed.permits(origin)) {
            return true;
        }
        // Never logs the origin at info: it is attacker-controlled text on an unauthenticated
        // endpoint, and this is the one place it would reach the log at volume.
        log.debug("refusing a websocket handshake from an origin that is not in cors_origins");
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    private static boolean originMatchesHost(String origin, String host) {
        int schemeEnd = origin.indexOf("://");
        return schemeEnd >= 0 && origin.substring(schemeEnd + 3).equals(host);
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        // Nothing to undo.
    }
}
