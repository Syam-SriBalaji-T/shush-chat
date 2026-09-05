package site.syamdev.shush.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The harness speaks only the public HTTP and WebSocket protocol, exactly as a browser would. */
final class ShushApi {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;

    ShushApi(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    Session newAnonymousUser() throws IOException, InterruptedException {
        JsonNode body = post("/api/auth/anonymous", "", null);
        return new Session(UUID.fromString(body.path("user").path("id").asText()),
                body.path("jwt").asText());
    }

    UUID createConversation(Session caller, Session other) throws IOException, InterruptedException {
        JsonNode body = post("/api/dev/conversations",
                "{\"otherUserId\":\"" + other.userId() + "\"}", caller.jwt());
        if (body.path("conversationId").isMissingNode()) {
            throw new IOException("could not create a conversation; is shush.dev-endpoints.enabled set? "
                    + "response was " + body);
        }
        return UUID.fromString(body.path("conversationId").asText());
    }

    /** Walks the whole history, oldest first. This is the durable record the sockets are checked against. */
    List<Long> persistedSeqs(Session caller, UUID conversationId) throws IOException, InterruptedException {
        List<Long> seqs = new ArrayList<>();
        Long before = null;
        while (true) {
            String path = "/api/conversations/" + conversationId + "/messages?limit=100"
                    + (before == null ? "" : "&before=" + before);
            JsonNode page = get(path, caller.jwt());

            List<Long> pageSeqs = new ArrayList<>();
            page.path("messages").forEach(m -> pageSeqs.add(m.path("seq").asLong()));
            seqs.addAll(0, pageSeqs);

            JsonNode next = page.path("nextBefore");
            if (next.isNull() || next.isMissingNode()) {
                return seqs;
            }
            before = next.asLong();
        }
    }

    private JsonNode get(String path, String jwt) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (jwt != null) {
            request.header("Authorization", "Bearer " + jwt);
        }
        return send(request.build());
    }

    private JsonNode post(String path, String body, String jwt) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (jwt != null) {
            request.header("Authorization", "Bearer " + jwt);
        }
        return send(request.build());
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException(request.method() + " " + request.uri().getPath()
                    + " returned " + response.statusCode() + ": " + response.body());
        }
        return response.body().isEmpty() ? json.createObjectNode() : json.readTree(response.body());
    }

    record Session(UUID userId, String jwt) {}
}
