package dev.cgt.pixelplace.tile.infra;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.flush.application.FlushTileSnapshot;
import dev.cgt.pixelplace.tile.domain.TileKey;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

/*
 * DB 후행 저장소의 복구 시작점과 flush snapshot을 같은 tiles row에 매핑하는 JPA entity
 * 실시간 authoritative state는 갖지 않으며 flush 입력 byte[]를 내부에 직접 공유하지 않음
 */
@Entity
@Table(name = "tiles")
public class TileEntity {

    // memory TileKey와 동일한 z/tx/ty 기준을 사용하는 tiles composite key
    @EmbeddedId
    private TileId id;

    // 1 byte/pixel palette index snapshot, capture/recovery 경계에서 정확한 tile shape 검증
    @Lob
    @Column(name = "data", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] data;

    // 마지막 DB flush snapshot version이며 runtime memory version과 항상 같지는 않음
    @Column(name = "tile_version", nullable = false)
    private long tileVersion;

    // DB 관리 관측 시각이며 event ordering·checkpoint 판정에는 사용하지 않음
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected TileEntity() {
    }

    /* captured key/bytes/tileVersion만 사용하고 live memory 재조회 없이 flush entity 생성 */
    public static TileEntity fromFlushSnapshot(FlushTileSnapshot snapshot) {
        FlushTileSnapshot source = Objects.requireNonNull(snapshot, "snapshot must not be null");
        TileKey key = Objects.requireNonNull(source.tileKey(), "snapshot tileKey must not be null");
        byte[] pixels = Objects.requireNonNull(source.pixels(), "snapshot pixels must not be null");
        if (pixels.length != BoardConstants.TILE_PIXEL_COUNT) {
            // 잘못된 BLOB shape를 checkpoint transaction에 포함하기 전 차단
            throw new IllegalArgumentException(
                    "snapshot pixels length must be " + BoardConstants.TILE_PIXEL_COUNT
            );
        }
        if (source.tileVersion() < 0) {
            // unsigned DB version과 recovery 증가 기준을 깨는 snapshot 거부
            throw new IllegalArgumentException("snapshot tileVersion must not be negative");
        }

        TileEntity entity = new TileEntity();
        entity.id = new TileId(key.z(), key.tx(), key.ty());
        entity.data = Arrays.copyOf(pixels, pixels.length);
        entity.tileVersion = source.tileVersion();
        return entity;
    }

    public TileId getId() {
        return id;
    }

    public int getZ() {
        return id.z;
    }

    public int getTx() {
        return id.tx;
    }

    public int getTy() {
        return id.ty;
    }

    public byte[] getData() {
        return Arrays.copyOf(data, data.length);
    }

    public long getTileVersion() {
        return tileVersion;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /*
     * tiles의 z/tx/ty composite primary key JPA 값 타입
     * memory TileKey와 물리 row 식별 기준 일치 보장
     */
    @Embeddable
    public static class TileId implements Serializable {

        @Column(name = "z", nullable = false)
        private int z;

        @Column(name = "tx", nullable = false)
        private int tx;

        @Column(name = "ty", nullable = false)
        private int ty;

        protected TileId() {
        }

        public TileId(int z, int tx, int ty) {
            this.z = z;
            this.tx = tx;
            this.ty = ty;
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

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TileId tileId)) {
                return false;
            }
            return z == tileId.z && tx == tileId.tx && ty == tileId.ty;
        }

        @Override
        public int hashCode() {
            return Objects.hash(z, tx, ty);
        }
    }
}
