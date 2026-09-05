package site.syamdev.shush.spike;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import site.syamdev.shush.support.AbstractPostgresIT;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpikeRecordRepositoryIT extends AbstractPostgresIT {

    @Autowired
    private SpikeRecordRepository repository;

    @Test
    void writesAndReadsBackARow() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        repository.saveAndFlush(new SpikeRecord(id, "phase zero", createdAt));

        assertThat(repository.findById(id))
                .get()
                .satisfies(found -> {
                    assertThat(found.getNote()).isEqualTo("phase zero");
                    assertThat(found.getCreatedAt()).isEqualTo(createdAt);
                });
    }
}
