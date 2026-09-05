package site.syamdev.shush.bench;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * The four properties the project claims. Each returns a description of what went wrong, or
 * nothing. Never relax one of these to make a run pass -- a failure here means the system is
 * wrong, and that is the entire point of the harness.
 */
final class Invariants {

    private Invariants() {
    }

    /**
     * Every sequence number from 1..N appears exactly once. A gap is a lost write; a repeat is
     * a double write. Either would break the claim that the log has one total order.
     */
    static List<String> contiguousSeqs(UUID conversationId, List<JsonNode> observed, int expected) {
        List<String> failures = new ArrayList<>();
        List<Long> seqs = observed.stream().map(frame -> frame.path("seq").asLong()).sorted().toList();
        List<Long> wanted = IntStream.rangeClosed(1, expected).mapToObj(Long::valueOf).toList();

        if (!seqs.equals(wanted)) {
            Set<Long> seen = new HashSet<>(seqs);
            List<Long> missing = wanted.stream().filter(seq -> !seen.contains(seq)).limit(10).toList();
            List<Long> repeated = seqs.stream()
                    .filter(seq -> seqs.indexOf(seq) != seqs.lastIndexOf(seq))
                    .distinct().limit(10).toList();
            failures.add("conversation %s: expected seq 1..%d, saw %d frames%s%s".formatted(
                    conversationId, expected, seqs.size(),
                    missing.isEmpty() ? "" : "; missing " + missing,
                    repeated.isEmpty() ? "" : "; repeated " + repeated));
        }
        return failures;
    }

    /**
     * Both participants observed the same order. Comparing arrival order, not contents: two
     * sockets holding the same set of messages in different orders is exactly the interleaving
     * this design exists to prevent.
     */
    static List<String> identicalOrder(UUID conversationId, List<JsonNode> asFirst, List<JsonNode> asSecond) {
        List<String> failures = new ArrayList<>();
        List<Long> first = asFirst.stream().map(frame -> frame.path("seq").asLong()).toList();
        List<Long> second = asSecond.stream().map(frame -> frame.path("seq").asLong()).toList();

        if (!first.equals(second)) {
            int divergence = 0;
            while (divergence < Math.min(first.size(), second.size())
                    && first.get(divergence).equals(second.get(divergence))) {
                divergence++;
            }
            failures.add("conversation %s: participants disagree on order from position %d (%s vs %s)"
                    .formatted(conversationId, divergence,
                            window(first, divergence), window(second, divergence)));
        }
        return failures;
    }

    /** No clientMsgId is ever delivered twice: at-least-once transport, exactly-once effect. */
    static List<String> noDuplicateDeliveries(UUID conversationId, List<JsonNode> observed) {
        List<String> failures = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode frame : observed) {
            String clientMsgId = frame.path("clientMsgId").asText();
            if (!seen.add(clientMsgId)) {
                failures.add("conversation %s: clientMsgId %s was delivered more than once"
                        .formatted(conversationId, clientMsgId));
            }
        }
        return failures;
    }

    /** What was sent, what the sockets saw, and what Postgres holds must all be the same count. */
    static List<String> nothingLost(UUID conversationId, int sent, int observed, List<Long> persisted) {
        List<String> failures = new ArrayList<>();
        if (observed != sent) {
            failures.add("conversation %s: sent %d messages but observed %d"
                    .formatted(conversationId, sent, observed));
        }
        if (persisted.size() != sent) {
            failures.add("conversation %s: sent %d messages but %d are persisted"
                    .formatted(conversationId, sent, persisted.size()));
        }
        List<Long> wanted = IntStream.rangeClosed(1, sent).mapToObj(Long::valueOf).toList();
        if (!persisted.equals(wanted)) {
            failures.add("conversation %s: persisted history is not 1..%d in order"
                    .formatted(conversationId, sent));
        }
        return failures;
    }

    private static String window(List<Long> seqs, int around) {
        int from = Math.max(0, around - 2);
        int to = Math.min(seqs.size(), around + 3);
        return seqs.subList(from, to).toString();
    }
}
