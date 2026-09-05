package site.syamdev.shush.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Which replica this is. Used to label frames and metrics, and to own scheduler locks later.
 *
 * <p>It is deliberately *not* used for routing. No client is ever pinned to a node and no
 * message is addressed to one: routing goes through the Redis backplane, which is what makes
 * sticky sessions unnecessary.
 */
@Component
public class NodeIdentity {

    private final String nodeId;

    NodeIdentity(@Value("${shush.node-id:}") String configured) {
        this.nodeId = (configured == null || configured.isBlank())
                ? "node-" + UUID.randomUUID().toString().substring(0, 8)
                : configured;
    }

    public String nodeId() {
        return nodeId;
    }
}
