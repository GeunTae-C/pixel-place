package dev.cgt.pixelplace.pixel.infra;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/*
 * WAL 승인 이벤트를 DB 생성값 없이 eventSeq assigned ID로 보존하는 append-only JPA entity
 * Persistable new-state를 명시하여 saveAll이 기존 assigned ID를 merge/update하는 경로 차단
 */
@Entity
@Table(name = "pixel_events")
public class PixelEventEntity implements Persistable<Long> {

    @Id
    @Column(name = "event_seq", nullable = false)
    private long eventSeq;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "z", nullable = false)
    private int z;

    @Column(name = "tx", nullable = false)
    private int tx;

    @Column(name = "ty", nullable = false)
    private int ty;

    @Column(name = "x", nullable = false)
    private int x;

    @Column(name = "y", nullable = false)
    private int y;

    @Column(name = "color", nullable = false)
    private int color;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // assigned ID 신규 row만 persist 경로로 보내 기존 event update 가능성 차단
    @Transient
    private boolean newEntity = true;

    protected PixelEventEntity() {
    }

    private PixelEventEntity(WalRecord record) {
        validate(record);
        this.eventSeq = record.eventSeq();
        this.userId = record.userId();
        this.z = record.z();
        this.tx = record.tx();
        this.ty = record.ty();
        this.x = record.x();
        this.y = record.y();
        this.color = record.color();
        // DATETIME(3) mapping 경계에서만 절삭, WAL과 immutable plan 원본은 변경 없음
        this.createdAt = record.createdAt().truncatedTo(ChronoUnit.MILLIS);
    }

    /* WAL 승인 시각 원본을 DB schema 정밀도에 맞춰 명시적으로 매핑하는 유일한 생성 경계 */
    public static PixelEventEntity fromWalRecord(WalRecord record) {
        return new PixelEventEntity(Objects.requireNonNull(record, "record must not be null"));
    }

    @Override
    public Long getId() {
        return eventSeq;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    /* persist/load 이후 같은 entity instance를 다시 신규 append로 오인하지 않도록 상태 전환 */
    @PostPersist
    @PostLoad
    void markNotNew() {
        newEntity = false;
    }

    public long getEventSeq() {
        return eventSeq;
    }

    public long getUserId() {
        return userId;
    }

    public int getZ() {
        return z;
    }

    public int getTx() {
        return tx;
    }

    public int getTy() {
        return ty;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getColor() {
        return color;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    private static void validate(WalRecord record) {
        if (record.eventSeq() <= 0) {
            // assigned primary key와 전역 승인 순서로 사용할 수 없는 값 거부
            throw new IllegalArgumentException("eventSeq must be positive.");
        }
        if (record.userId() <= 0) {
            // unsigned user_id와 감사 주체 계약을 동시에 위반하는 record 거부
            throw new IllegalArgumentException("userId must be positive.");
        }
        if (record.z() != BoardConstants.Z0_LEVEL) {
            // 현재 tiles snapshot과 replay가 지원하는 canonical level 밖 event 저장 금지
            throw new IllegalArgumentException("Only canonical z=0 WAL events can be persisted.");
        }
        if (record.x() < 0 || record.x() >= BoardConstants.BOARD_SIZE
                || record.y() < 0 || record.y() >= BoardConstants.BOARD_SIZE) {
            throw new IllegalArgumentException("WAL coordinates are outside the board.");
        }
        int expectedTx = record.x() / BoardConstants.TILE_SIZE;
        int expectedTy = record.y() / BoardConstants.TILE_SIZE;
        if (record.tx() != expectedTx || record.ty() != expectedTy) {
            // event와 tile snapshot이 다른 row를 가리키는 WAL은 transaction 진입 전제 위반
            throw new IllegalArgumentException("WAL tile coordinates do not match pixel coordinates.");
        }
        if (record.color() < 0 || record.color() >= BoardConstants.PALETTE_SIZE) {
            // memory replay로 재현할 수 없는 palette index의 append-only 보존 금지
            throw new IllegalArgumentException("WAL color is outside the palette.");
        }
        Objects.requireNonNull(record.createdAt(), "createdAt must not be null");
    }
}
