package site.syamdev.shush.bench;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Builds the conversations both modes run against: two anonymous users and two sockets each. */
final class Fixtures {

    private Fixtures() {
    }

    static List<Pair> conversations(BenchOptions options, ShushApi api, HttpClient http) throws Exception {
        List<Pair> pairs = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Pair>> futures = new ArrayList<>();
            for (int i = 0; i < options.conversations(); i++) {
                futures.add(pool.submit(() -> openConversation(options, api, http)));
            }
            for (Future<Pair> future : futures) {
                pairs.add(future.get());
            }
        }
        return pairs;
    }

    private static Pair openConversation(BenchOptions options, ShushApi api, HttpClient http)
            throws Exception {
        ShushApi.Session first = api.newAnonymousUser();
        ShushApi.Session second = api.newAnonymousUser();
        UUID conversationId = api.createConversation(first, second);
        return new Pair(conversationId,
                new Participant(first, BenchSocket.open(http, options.websocketUrl(), first.jwt())),
                new Participant(second, BenchSocket.open(http, options.websocketUrl(), second.jwt())));
    }

    record Participant(ShushApi.Session session, BenchSocket socket) {}

    record Pair(UUID conversationId, Participant first, Participant second) {

        List<Participant> participants() {
            return List.of(first, second);
        }

        List<BenchSocket> sockets() {
            return List.of(first.socket(), second.socket());
        }

        void close() {
            first.socket().close();
            second.socket().close();
        }
    }
}
