package site.syamdev.shush.media;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import site.syamdev.shush.support.AbstractIT;
import site.syamdev.shush.support.TestUsers;
import site.syamdev.shush.support.WsClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The media path exists to keep bytes away from the API. These tests exercise it the way a
 * browser does: ask for a URL, PUT straight to storage, then send a message that names the key.
 */
class MediaIT extends AbstractIT {

    /** A one-pixel PNG, so the bytes are real without being large. */
    private static final byte[] TINY_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @LocalServerPort
    private int port;

    @Autowired
    private MediaObjectRepository mediaObjects;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void anUploadUrlIsIssuedAndTheImageArrivesAsAMessage() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        JsonNode upload = uploadUrl(alice, conversationId, "image/png", TINY_PNG.length).getBody();
        assertThat(upload.path("key").asText()).startsWith("media/" + conversationId + "/");
        assertThat(upload.path("uploadUrl").asText()).contains("X-Amz-Signature");

        // The row exists before the bytes do, so an abandoned upload is still findable.
        assertThat(mediaObjects.findById(upload.path("key").asText()))
                .get()
                .extracting(MediaObject::getStatus)
                .isEqualTo(MediaObject.Status.PENDING);

        putBytes(upload.path("uploadUrl").asText(), "image/png", TINY_PNG);

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt());
             WsClient bobWs = WsClient.connect(port, bob.jwt())) {
            aliceWs.send("{\"type\":\"send\",\"conversationId\":\"" + conversationId
                    + "\",\"clientMsgId\":\"" + UUID.randomUUID()
                    + "\",\"kind\":\"image\",\"mediaKey\":\"" + upload.path("key").asText() + "\"}");

            JsonNode received = bobWs.await("message");
            assertThat(received.path("kind").asText()).isEqualTo("image");
            assertThat(received.path("mediaKey").asText()).isEqualTo(upload.path("key").asText());
        }

        // Confirmed only once storage was actually asked, and with the size storage reports
        // rather than the size the client claimed.
        assertThat(mediaObjects.findById(upload.path("key").asText()))
                .get()
                .satisfies(object -> {
                    assertThat(object.getStatus()).isEqualTo(MediaObject.Status.CONFIRMED);
                    assertThat(object.getSizeBytes()).isEqualTo(TINY_PNG.length);
                });
    }

    @Test
    void anOversizeImageIsRefused() {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        ResponseEntity<JsonNode> response = uploadUrl(alice, conversationId, "image/png", 6_000_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().path("code").asText()).isEqualTo("too_large");
    }

    @Test
    void aTypeOutsideTheAllowlistIsRefused() {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        // An allowlist, so this fails for not being on it rather than for being recognised.
        assertThat(uploadUrl(alice, conversationId, "application/zip", 1024).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(uploadUrl(alice, conversationId, "text/html", 1024).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aNonParticipantCannotGetAnUploadUrl() {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        TestUsers.Session mallory = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        assertThat(uploadUrl(mallory, conversationId, "image/png", 1024).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * A message naming a key whose bytes were never uploaded must not reach anyone: every
     * recipient would get a picture that is not there.
     */
    @Test
    void aMessageWhoseImageWasNeverUploadedIsNotDelivered() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        String key = uploadUrl(alice, conversationId, "image/png", TINY_PNG.length)
                .getBody().path("key").asText();

        try (WsClient aliceWs = WsClient.connect(port, alice.jwt());
             WsClient bobWs = WsClient.connect(port, bob.jwt())) {
            // Never uploaded.
            aliceWs.send("{\"type\":\"send\",\"conversationId\":\"" + conversationId
                    + "\",\"clientMsgId\":\"" + UUID.randomUUID()
                    + "\",\"kind\":\"image\",\"mediaKey\":\"" + key + "\"}");
            aliceWs.awaitAck("sent");

            // A real message afterwards proves the image one was dropped rather than merely slow.
            aliceWs.sendText(conversationId, UUID.randomUUID(), "text still works");
            JsonNode received = bobWs.await("message");
            assertThat(received.path("body").asText()).isEqualTo("text still works");
            assertThat(received.path("seq").asLong())
                    .as("the dropped image never took a sequence number")
                    .isEqualTo(1L);
        }
    }

    @Test
    void anImageCanBeReadBackByAParticipantAndNotByAnyoneElse() throws Exception {
        TestUsers.Session alice = testUsers.newAnonymous();
        TestUsers.Session bob = testUsers.newAnonymous();
        TestUsers.Session mallory = testUsers.newAnonymous();
        UUID conversationId = testUsers.createConversation(alice, bob);

        JsonNode upload = uploadUrl(alice, conversationId, "image/png", TINY_PNG.length).getBody();
        putBytes(upload.path("uploadUrl").asText(), "image/png", TINY_PNG);
        String key = upload.path("key").asText();

        // A redirect, never bytes: the API hands back a location.
        ResponseEntity<Void> asBob = rest.exchange("/api/media/" + key, HttpMethod.GET,
                new HttpEntity<>(testUsers.authorised(bob)), Void.class);
        assertThat(asBob.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(asBob.getHeaders().getLocation()).isNotNull();

        // Membership decides, not possession of the key.
        assertThat(rest.exchange("/api/media/" + key, HttpMethod.GET,
                new HttpEntity<>(testUsers.authorised(mallory)), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<JsonNode> uploadUrl(TestUsers.Session caller, UUID conversationId,
                                               String mime, long sizeBytes) {
        return rest.exchange("/api/media/upload-url", HttpMethod.POST,
                new HttpEntity<>(Map.of("conversationId", conversationId.toString(),
                        "mime", mime, "sizeBytes", sizeBytes), testUsers.authorised(caller)),
                JsonNode.class);
    }

    private void putBytes(String url, String contentType, byte[] bytes) throws Exception {
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode())
                .as("the browser uploads straight to storage; the API is not involved")
                .isBetween(200, 299);
    }

}
