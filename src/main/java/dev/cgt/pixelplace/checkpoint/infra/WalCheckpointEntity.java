package dev.cgt.pixelplace.checkpoint.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

// wal_checkpoint 테이블 매핑 엔티티다.
// 이 엔티티는 DB row와 Java 객체 사이의 단순 매핑만 담당하며, recovery orchestration 책임은 갖지 않는다.
@Entity
@Table(name = "wal_checkpoint")
public class WalCheckpointEntity {

    @Id
    @Column(name = "checkpoint_name", nullable = false, length = 64)
    private String checkpointName;

    // checkpoint는 DB가 어디까지 완전히 flush됐는지 나타내는 recovery 시작 기준점이다.
    // 이 값 이하의 이벤트는 pixel_events와 tiles 양쪽에 모두 반영 완료된 상태여야 한다.
    @Column(name = "last_flushed_event_seq", nullable = false)
    private long lastFlushedEventSeq;

    // checkpoint 갱신 시점을 확인하기 위한 DB 메타 컬럼이다.
    // 애플리케이션이 recovery 흐름 제어용으로 직접 갱신하는 값은 아니다.
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected WalCheckpointEntity() {
    }

    public String getCheckpointName() {
        return checkpointName;
    }

    public long getLastFlushedEventSeq() {
        return lastFlushedEventSeq;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
