package site.syamdev.shush.presence;

import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import site.syamdev.shush.support.AbstractIT;
import site.syamdev.shush.support.TestUsers;
import site.syamdev.shush.support.WsClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceIT extends AbstractIT {

    @LocalServerPort
    private int port;

    @Autowired
    private PresenceService presence;

    /**
     * The property the whole design rests on: presence is asserted by a key that has to be
     * renewed, so a node that dies stops asserting anything and nobody is left online forever.
     * Nothing cleans this up -- it simply stops being true.
     */
    @Test
    void presenceExpiresOnItsOwnWithoutAHeartbeat() {
        UUID abandoned = UUID.randomUUID();
        presence.markOnline(abandoned);
        assertThat(presence.isOnline(abandoned)).isTrue();

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(presence.isOnline(abandoned)).isFalse());
    }

    @Test
    void connectingMarksAUserOnlineAndTellsTheOtherPerson() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        try (WsClient bobWs = WsClient.connect(port, bob.jwt())) {
            bobWs.await("hello");

            try (WsClient aliceWs = WsClient.connect(port, alice.jwt())) {
                aliceWs.await("hello");

                JsonNode online = bobWs.await("presence");
                assertThat(online.path("userId").asText()).isEqualTo(alice.userId().toString());
                assertThat(online.path("online").asBoolean()).isTrue();
                assertThat(online.path("conversationId").asText()).isEqualTo(conversationId.toString());

                Awaitility.await().atMost(Duration.ofSeconds(5))
                        .untilAsserted(() -> assertThat(presence.isOnline(alice.userId())).isTrue());
            }

            // Alice's socket closed. The conversation stays open -- she might come back -- so
            // this is "gone offline", not "left".
            JsonNode offline = bobWs.await("presence",
                    frame -> !frame.path("online").asBoolean(), "presence with online=false");
            assertThat(offline.path("userId").asText()).isEqualTo(alice.userId().toString());
        }
    }

    @Test
    void aSecondTabDoesNotChangePresenceAndClosingItDoesNotEitherWhileOneRemains() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        testUsers.createConversation(alice, bob);

        try (WsClient firstTab = WsClient.connect(port, alice.jwt())) {
            firstTab.await("hello");
            assertThat(presence.isOnline(alice.userId())).isTrue();

            try (WsClient secondTab = WsClient.connect(port, alice.jwt())) {
                secondTab.await("hello");
                assertThat(presence.isOnline(alice.userId())).isTrue();
            }

            // Closing one of two tabs is not going offline.
            assertThat(presence.isOnline(alice.userId())).isTrue();
        }
    }

    @Test
    void bulkPresenceAnswersForAWholeListInOneCall() {
        UUID here = UUID.randomUUID();
        UUID away = UUID.randomUUID();
        presence.markOnline(here);

        assertThat(presence.onlineAmong(java.util.List.of(here, away)))
                .containsEntry(here, true)
                .containsEntry(away, false);
    }
}
