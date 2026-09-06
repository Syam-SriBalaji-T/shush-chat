package site.syamdev.shush.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/**
 * Closes every socket this node holds with {@code GOING_AWAY} when the node is shutting down,
 * so clients learn to reconnect immediately rather than waiting for a TCP timeout to notice.
 *
 * <p>This is what makes a *planned* restart invisible. It does nothing for a hard kill, which is
 * exactly why the chaos harness kills rather than stops: correctness must not depend on the
 * process getting a chance to tidy up.
 */
@Component
class GracefulSocketShutdown {

    private static final Logger log = LoggerFactory.getLogger(GracefulSocketShutdown.class);

    private final SessionRegistry registry;

    GracefulSocketShutdown(SessionRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ContextClosedEvent.class)
    void drainSessions() {
        int open = registry.openConnectionCount();
        if (open == 0) {
            return;
        }
        log.info("draining {} websocket session(s) before shutdown", open);
        for (WebSocketSession session : registry.allSessions()) {
            try {
                session.close(CloseStatus.GOING_AWAY);
            } catch (IOException | IllegalStateException e) {
                log.debug("session {} was already gone", session.getId());
            }
        }
    }
}
