package site.syamdev.shush.bench;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Opens two sockets per conversation, has both participants send at once, and then checks the
 * four invariants against what each socket actually received and what the database actually holds.
 *
 * <p>Both participants sending concurrently is the point. A harness where one side sends and the
 * other only listens cannot detect reordering, because there is nothing to reorder against.
 */
final class OrderingBench {

    private final BenchOptions options;
    private final ShushApi api;
    private final HttpClient http = HttpClient.newHttpClient();

    OrderingBench(BenchOptions options) {
        this.options = options;
        this.api = new ShushApi(options.baseUrl());
    }

    Result run() throws Exception {
        System.out.printf("setting up %d conversations (%d messages each, %d total)%n",
                options.conversations(), options.messages(), options.totalMessages());

        List<Pair> pairs = setUp();
        System.out.printf("connected %d sockets%n", pairs.size() * 2);

        Instant startedAt = Instant.now();
        sendConcurrently(pairs);
        // Frames written, not yet acknowledged: the socket send returns once the frame is on
        // the wire. End-to-end timing is measured below, when the last delivery arrives.
        System.out.printf("all %d frames written in %s; waiting for delivery%n",
                options.totalMessages(), Duration.between(startedAt, Instant.now()));

        List<BenchSocket> sockets = pairs.stream()
                .flatMap(pair -> List.of(pair.first().socket(), pair.second().socket()).stream())
                .toList();
        boolean settled = BenchSocket.awaitQuiescence(sockets, options.messages(), options.settleTimeout());
        Duration elapsed = Duration.between(startedAt, Instant.now());

        List<String> failures = new ArrayList<>();
        if (!settled) {
            failures.add("timed out after %s waiting for every socket to receive %d messages"
                    .formatted(options.settleTimeout(), options.messages()));
        }
        failures.addAll(check(pairs));

        Set<String> nodes = new HashSet<>();
        for (BenchSocket socket : sockets) {
            if (socket.servedByNode() != null) {
                nodes.add(socket.servedByNode());
            }
        }
        if (options.assertMultinode() && nodes.size() < 2) {
            failures.add("expected at least two nodes to serve this run but saw " + nodes
                    + "; the run proves nothing about cross-node fanout");
        }

        pairs.forEach(Pair::close);
        return new Result(failures, elapsed, options.totalMessages(), nodes);
    }

    private List<Pair> setUp() throws Exception {
        List<Pair> pairs = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Pair>> futures = new ArrayList<>();
            for (int i = 0; i < options.conversations(); i++) {
                futures.add(pool.submit(this::openConversation));
            }
            for (Future<Pair> future : futures) {
                pairs.add(future.get());
            }
        }
        return pairs;
    }

    private Pair openConversation() throws Exception {
        ShushApi.Session first = api.newAnonymousUser();
        ShushApi.Session second = api.newAnonymousUser();
        UUID conversationId = api.createConversation(first, second);
        return new Pair(conversationId,
                new Participant(first, BenchSocket.open(http, options.websocketUrl(), first.jwt())),
                new Participant(second, BenchSocket.open(http, options.websocketUrl(), second.jwt())));
    }

    private void sendConcurrently(List<Pair> pairs) throws Exception {
        CountDownLatch startTogether = new CountDownLatch(1);
        try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> sending = new ArrayList<>();
            for (Pair pair : pairs) {
                for (Participant participant : List.of(pair.first(), pair.second())) {
                    sending.add(senders.submit(() -> {
                        startTogether.await();
                        for (int i = 0; i < options.messagesPerParticipant(); i++) {
                            participant.socket().sendText(pair.conversationId(), UUID.randomUUID(), "m" + i);
                        }
                        return null;
                    }));
                }
            }
            startTogether.countDown();
            for (Future<?> future : sending) {
                future.get();
            }
        }
    }

    private List<String> check(List<Pair> pairs) throws Exception {
        List<String> failures = new ArrayList<>();
        for (Pair pair : pairs) {
            List<JsonNode> asFirst = pair.first().socket().framesOfType("message");
            List<JsonNode> asSecond = pair.second().socket().framesOfType("message");
            List<Long> persisted = api.persistedSeqs(pair.first().session(), pair.conversationId());

            failures.addAll(Invariants.contiguousSeqs(pair.conversationId(), asFirst, options.messages()));
            failures.addAll(Invariants.identicalOrder(pair.conversationId(), asFirst, asSecond));
            failures.addAll(Invariants.noDuplicateDeliveries(pair.conversationId(), asFirst));
            failures.addAll(Invariants.nothingLost(pair.conversationId(),
                    options.messages(), asFirst.size(), persisted));
        }
        return failures;
    }

    record Participant(ShushApi.Session session, BenchSocket socket) {}

    record Pair(UUID conversationId, Participant first, Participant second) {

        void close() {
            first.socket().close();
            second.socket().close();
        }
    }

    record Result(List<String> failures, Duration elapsed, int messages, Set<String> nodes) {

        boolean passed() {
            return failures.isEmpty();
        }
    }
}
