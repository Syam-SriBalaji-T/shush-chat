package site.syamdev.shush.realtime;

import site.syamdev.shush.message.Message;

import java.util.UUID;

/**
 * How a persisted message reaches the sockets that should see it.
 *
 * <p>Phase 2 resolves this against the node-local registry, which is only correct because
 * there is one node. Phase 3 replaces the implementation with a Redis publish for every
 * recipient -- including ones connected to this very node -- so there is exactly one delivery
 * path. Two paths is how same-node quietly works while cross-node is broken.
 */
public interface MessageDispatcher {

    /** Fans a newly persisted message out to every participant in its conversation. */
    void deliver(Message message);

    /** Tells the sender its message is durable and sequenced. */
    void acknowledge(UUID senderId, Message message, boolean duplicate);
}
