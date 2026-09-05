package site.syamdev.shush.spike;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import site.syamdev.shush.support.AbstractPostgresIT;

import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase 0 exit criteria require the WebSocket round-trip to be proven by an automated
 * test rather than by hand, so this drives a real client against the running server.
 */
class EchoWebSocketIT extends AbstractPostgresIT {

    @LocalServerPort
    private int port;

    @Test
    void echoesFramesBackToTheSender() throws Exception {
        BlockingQueue<String> received = new ArrayBlockingQueue<>(4);

        WebSocketSession session = new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                        received.add(message.getPayload());
                    }
                }, null, URI.create("ws://localhost:" + port + "/ws/echo"))
                .get(10, TimeUnit.SECONDS);

        try {
            session.sendMessage(new TextMessage("ping"));
            assertThat(received.poll(10, TimeUnit.SECONDS)).isEqualTo("ping");

            session.sendMessage(new TextMessage("pong"));
            assertThat(received.poll(10, TimeUnit.SECONDS)).isEqualTo("pong");
        } finally {
            session.close();
        }
    }
}
