package site.syamdev.shush.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A harness that cannot fail proves nothing. Each case here feeds an invariant a stream that
 * violates it and asserts the violation is reported -- so a green benchmark run is evidence
 * about the system rather than evidence the checks are inert.
 */
class InvariantsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID CONVERSATION = UUID.randomUUID();

    @Test
    void contiguousSeqsAcceptsACompleteRun() {
        assertThat(Invariants.contiguousSeqs(CONVERSATION, frames(1, 2, 3, 4), 4)).isEmpty();
    }

    @Test
    void contiguousSeqsCatchesAGap() {
        assertThat(Invariants.contiguousSeqs(CONVERSATION, frames(1, 2, 4), 3))
                .singleElement().asString().contains("missing [3]");
    }

    @Test
    void contiguousSeqsCatchesARepeat() {
        assertThat(Invariants.contiguousSeqs(CONVERSATION, frames(1, 2, 2), 3))
                .singleElement().asString().contains("repeated [2]");
    }

    @Test
    void identicalOrderAcceptsTwoAgreeingObservers() {
        assertThat(Invariants.identicalOrder(CONVERSATION, frames(1, 2, 3), frames(1, 2, 3))).isEmpty();
    }

    @Test
    void identicalOrderCatchesInterleavingEvenWhenBothSawEveryMessage() {
        // The same set, a different order. This is exactly the failure the partition key prevents,
        // and a check that only compared contents would call it a pass.
        assertThat(Invariants.identicalOrder(CONVERSATION, frames(1, 2, 3), frames(1, 3, 2)))
                .singleElement().asString().contains("disagree on order from position 1");
    }

    @Test
    void noDuplicateDeliveriesCatchesARepeatedClientMsgId() {
        String repeated = UUID.randomUUID().toString();
        List<JsonNode> observed = List.of(
                frame(1, UUID.randomUUID().toString()),
                frame(2, repeated),
                frame(3, repeated));

        assertThat(Invariants.noDuplicateDeliveries(CONVERSATION, observed))
                .singleElement().asString().contains("delivered more than once");
    }

    @Test
    void nothingLostAcceptsAMatchingRun() {
        assertThat(Invariants.nothingLost(CONVERSATION, 3, 3, List.of(1L, 2L, 3L))).isEmpty();
    }

    @Test
    void nothingLostCatchesAMessageThatNeverReachedTheSocket() {
        assertThat(Invariants.nothingLost(CONVERSATION, 3, 2, List.of(1L, 2L, 3L)))
                .anySatisfy(failure -> assertThat(failure).contains("sent 3 messages but observed 2"));
    }

    @Test
    void nothingLostCatchesAMessageThatNeverReachedPostgres() {
        assertThat(Invariants.nothingLost(CONVERSATION, 3, 3, List.of(1L, 2L)))
                .anySatisfy(failure -> assertThat(failure).contains("but 2 are persisted"));
    }

    private static List<JsonNode> frames(int... seqs) {
        return IntStream.of(seqs)
                .mapToObj(seq -> frame(seq, UUID.randomUUID().toString()))
                .toList();
    }

    private static JsonNode frame(long seq, String clientMsgId) {
        return JSON.createObjectNode()
                .put("type", "message")
                .put("seq", seq)
                .put("clientMsgId", clientMsgId);
    }
}
