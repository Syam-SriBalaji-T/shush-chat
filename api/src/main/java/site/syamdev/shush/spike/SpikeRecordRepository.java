package site.syamdev.shush.spike;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpikeRecordRepository extends JpaRepository<SpikeRecord, UUID> {
}
