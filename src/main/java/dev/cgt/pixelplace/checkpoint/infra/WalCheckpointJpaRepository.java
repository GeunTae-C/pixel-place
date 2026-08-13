package dev.cgt.pixelplace.checkpoint.infra;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// "main" checkpoint row를 읽기 위한 최소 JPA repository
// checkpoint는 WAL replay 시작 범위를 결정하므로, recovery에서 조용히 기본값으로 대체하면 안됨
public interface WalCheckpointJpaRepository extends JpaRepository<WalCheckpointEntity, String> {

    // ambiguous transaction 종료 전 이전 checkpoint를 관측하지 않도록 main row write lock 획득
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select checkpoint from WalCheckpointEntity checkpoint where checkpoint.checkpointName = 'main'")
    Optional<WalCheckpointEntity> lockMainCheckpoint();

    // 선행 event/tile JPA write flush 뒤 expected가 그대로인 경우만 target 전진
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE wal_checkpoint
            SET last_flushed_event_seq = :target
            WHERE checkpoint_name = 'main'
              AND last_flushed_event_seq = :expected
            """, nativeQuery = true)
    int advanceMainCheckpoint(
            @Param("expected") long expectedLastFlushedEventSeq,
            @Param("target") long flushTargetEventSeq
    );
}
