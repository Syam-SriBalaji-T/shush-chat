package site.syamdev.shush.bench;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

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

    /**
     * The log holds exactly 1..N.
     *
     * <p>Separate from {@link #nothingLost} because across a node failure the two participants
     * legitimately observe different subsets over their sockets, so comparing socket counts to
     * the sent count would be meaningless. What must still hold absolutely is the durable record.
     */
    static List<String> persistedIsExactly(UUID conversationId, int expected, List<Long> persisted) {
        List<String> failures = new ArrayList<>();
        List<Long> wanted = IntStream.rangeClosed(1, expected).mapToObj(Long::valueOf).toList();
        if (!persisted.equals(wanted)) {
            failures.add("conversation %s: expected %d persisted messages numbered 1..%d in order, got %d"
                    .formatted(conversationId, expected, expected, persisted.size()));
        }
        return failures;
    }

    /**
     * A single observer never went backwards. Across a node failure the two participants
     * legitimately see different *subsets* -- one was disconnected for a while -- so the
     * cross-observer comparison no longer applies, but neither stream may reorder. Both streams
     * being ascending over the same 1..N is what makes their common messages agree.
     */
    static List<String> strictlyAscending(UUID conversationId, String who, List<JsonNode> observed) {
        List<String> failures = new ArrayList<>();
        long previous = 0;
        for (JsonNode frame : observed) {
            long seq = frame.path("seq").asLong();
            if (seq <= previous) {
                failures.add("conversation %s: %s received seq %d after seq %d"
                        .formatted(conversationId, who, seq, previous));
                return failures;
            }
            previous = seq;
        }
        return failures;
    }

    /**
     * A client that missed messages while its node was down can ask for them and get exactly
     * what it missed, in order. Durability is worth nothing if there is no way to collect it.
     */
    static List<String> resumeIsComplete(UUID conversationId, String who,
                                         long resumeFrom, List<Long> resumed, int expected) {
        List<String> failures = new ArrayList<>();
        List<Long> wanted = LongStream.rangeClosed(resumeFrom + 1, expected).boxed().toList();
        if (!resumed.equals(wanted)) {
            failures.add("conversation %s: %s resumed from seq %d and got %d message(s) instead of %d in order"
                    .formatted(conversationId, who, resumeFrom, resumed.size(), wanted.size()));
        }
        return failures;
    }

    /** Every message that was sent is in the log exactly once, whatever happened to the nodes. */
    static List<String> everySentMessagePersistedOnce(UUID conversationId,
                                                      Collection<UUID> sentClientMsgIds,
                                                      List<String> persistedClientMsgIds) {
        List<String> failures = new ArrayList<>();
        Set<String> persisted = new HashSet<>(persistedClientMsgIds);

        if (persisted.size() != persistedClientMsgIds.size()) {
            failures.add("conversation %s: the log holds a clientMsgId more than once"
                    .formatted(conversationId));
        }
        List<UUID> missing = sentClientMsgIds.stream()
                .filter(id -> !persisted.contains(id.toString()))
                .limit(10)
                .toList();
        if (!missing.isEmpty()) {
            failures.add("conversation %s: %d acknowledged message(s) are not in the log, e.g. %s"
                    .formatted(conversationId, missing.size(), missing));
        }
        return failures;
    }

    private static String window(List<Long> seqs, int around) {
        int from = Math.max(0, around - 2);
        int to = Math.min(seqs.size(), around + 3);
        return seqs.subList(from, to).toString();
    }
}
