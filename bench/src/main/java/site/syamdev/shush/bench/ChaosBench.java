package site.syamdev.shush.bench;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The highest-value run in the project: the same invariants as the ordering mode, but with a
 * replica killed outright while traffic is flowing.
 *
 * <p>Three things make it a real test rather than a demonstration. Senders retry with the
 * <em>same</em> clientMsgId, because a client that never saw its ack cannot know whether the
 * message got through — so the dedup constraint is genuinely exercised rather than assumed.
 * Clients whose node died reconnect through the load balancer and land somewhere else, which
 * only works because nothing is sticky. And the run fails if nothing actually reconnected: a
 * kill that disturbed no one would let every invariant pass while proving nothing.
 */
final class ChaosBench {

    private static final Duration SEND_GIVE_UP = Duration.ofSeconds(60);

    private final BenchOptions options;
    private final ShushApi api;
    private final HttpClient http = HttpClient.newHttpClient();

    ChaosBench(BenchOptions options) {
        this.options = options;
        this.api = new ShushApi(options.baseUrl());
    }

    Result run() throws Exception {
        System.out.printf("setting up %d conversations (%d messages each, %d total)%n",
                options.conversations(), options.messages(), options.totalMessages());
        List<Fixtures.Pair> pairs = Fixtures.conversations(options, api, http);
        System.out.printf("connected %d sockets across %s%n", pairs.size() * 2, nodesServing(pairs));
        System.out.printf("sending each participant's %d messages over ~%ds, killing %s at second %d%n",
                options.messagesPerParticipant(), options.sendWindow().toSeconds(),
                options.killNode(), options.killAtSecond());

        Instant startedAt = Instant.now();
        Map<UUID, Set<UUID>> sentPerConversation = new ConcurrentHashMap<>();
        List<String> failures = new ArrayList<>();

        Thread assassin = scheduleKill(failures);
        int unsent = sendConcurrently(pairs, sentPerConversation);
        assassin.join();

        if (unsent > 0) {
            failures.add("%d message(s) were never acknowledged, even after reconnecting and "
                    .formatted(unsent) + "retransmitting for " + SEND_GIVE_UP);
        }

        boolean settled = awaitEveryMessagePersisted(pairs);
        Duration elapsed = Duration.between(startedAt, Instant.now());
        if (!settled) {
            failures.add("timed out after %s waiting for every message to reach the log"
                    .formatted(options.settleTimeout()));
        }

        failures.addAll(check(pairs, sentPerConversation));

        int reconnects = pairs.stream()
                .flatMap(pair -> pair.sockets().stream())
                .mapToInt(BenchSocket::reconnectCount)
                .sum();
        if (reconnects == 0) {
            failures.add("no socket ever reconnected, so killing " + options.killNode()
                    + " disturbed nothing and this run proves nothing about node failure");
        }

        int retransmissions = pairs.stream()
                .flatMap(pair -> pair.sockets().stream())
                .mapToInt(BenchSocket::retransmissionCount)
                .sum();

        pairs.forEach(Fixtures.Pair::close);
        return new Result(failures, elapsed, options.totalMessages(), nodesServing(pairs),
                reconnects, retransmissions);
    }

    private Thread scheduleKill(List<String> failures) {
        return Thread.ofVirtual().start(() -> {
            try {
                TimeUnit.SECONDS.sleep(options.killAtSecond());
                Chaos.kill(options.killNode());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                synchronized (failures) {
                    failures.add("could not kill " + options.killNode() + ": " + e.getMessage());
                }
            }
        });
    }

    /** @return how many messages could not be sent at all */
    private int sendConcurrently(List<Fixtures.Pair> pairs, Map<UUID, Set<UUID>> sent) throws Exception {
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<Integer>> sending = new ArrayList<>();

        try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Fixtures.Pair pair : pairs) {
                Set<UUID> ids = sent.computeIfAbsent(pair.conversationId(),
                        id -> ConcurrentHashMap.newKeySet());
                for (Fixtures.Participant participant : pair.participants()) {
                    sending.add(senders.submit(() -> {
                        startTogether.await();
                        long gapNanos = options.sendInterval().toNanos();
                        int failed = 0;
                        for (int i = 0; i < options.messagesPerParticipant(); i++) {
                            UUID clientMsgId = UUID.randomUUID();
                            boolean ok = participant.socket()
                                    .sendAndAwaitAck(pair.conversationId(), clientMsgId, "m" + i, SEND_GIVE_UP);
                            if (ok) {
                                ids.add(clientMsgId);
                            } else {
                                failed++;
                            }
                            if (gapNanos > 0) {
                                TimeUnit.NANOSECONDS.sleep(gapNanos);
                            }
                        }
                        return failed;
                    }));
                }
            }
            startTogether.countDown();

            int unsent = 0;
            for (Future<Integer> future : sending) {
                unsent += future.get();
            }
            return unsent;
        }
    }

    /**
     * Waits on the log rather than on the sockets. A client whose node died was not connected
     * when some of these messages were delivered, so its socket will never see them — that is
     * what the resume check below is for, and treating it as a timeout would be wrong.
     */
    private boolean awaitEveryMessagePersisted(List<Fixtures.Pair> pairs) throws Exception {
        long deadline = System.nanoTime() + options.settleTimeout().toNanos();
        CountDownLatch tick = new CountDownLatch(1);
        while (System.nanoTime() < deadline) {
            boolean complete = true;
            for (Fixtures.Pair pair : pairs) {
                if (api.persistedSeqs(pair.first().session(), pair.conversationId()).size()
                        < options.messages()) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                return true;
            }
            tick.await(250, TimeUnit.MILLISECONDS);
        }
        return false;
    }

    private List<String> check(List<Fixtures.Pair> pairs, Map<UUID, Set<UUID>> sent) throws Exception {
        List<String> failures = new ArrayList<>();
        for (Fixtures.Pair pair : pairs) {
            UUID conversationId = pair.conversationId();

            List<Long> persisted = api.persistedSeqs(pair.first().session(), conversationId);
            failures.addAll(Invariants.persistedIsExactly(conversationId, options.messages(), persisted));
            failures.addAll(Invariants.everySentMessagePersistedOnce(conversationId,
                    sent.getOrDefault(conversationId, Set.of()),
                    api.persistedClientMsgIds(pair.first().session(), conversationId)));

            for (Fixtures.Participant participant : pair.participants()) {
                String who = participant.session().userId().toString().substring(0, 8);
                List<JsonNode> observed = participant.socket().framesOfType("message");

                failures.addAll(Invariants.strictlyAscending(conversationId, who, observed));
                failures.addAll(Invariants.noDuplicateDeliveries(conversationId, observed));

                // What a real client does after reconnecting: ask for everything after the last
                // sequence number it holds without a gap.
                long resumeFrom = lastContiguousSeq(observed);
                List<Long> resumed = api.seqsSince(participant.session(), conversationId, resumeFrom);
                failures.addAll(Invariants.resumeIsComplete(conversationId, who, resumeFrom,
                        resumed, options.messages()));
            }
        }
        return failures;
    }

    private static long lastContiguousSeq(List<JsonNode> observed) {
        Set<Long> seen = new HashSet<>();
        observed.forEach(frame -> seen.add(frame.path("seq").asLong()));
        long seq = 0;
        while (seen.contains(seq + 1)) {
            seq++;
        }
        return seq;
    }

    private static Set<String> nodesServing(List<Fixtures.Pair> pairs) {
        Set<String> nodes = new HashSet<>();
        pairs.stream().flatMap(pair -> pair.sockets().stream())
                .forEach(socket -> nodes.addAll(socket.nodesServed()));
        return nodes;
    }

    record Result(List<String> failures, Duration elapsed, int messages,
                  Set<String> nodes, int reconnects, int retransmissions) {

        boolean passed() {
            return failures.isEmpty();
        }
    }
}
