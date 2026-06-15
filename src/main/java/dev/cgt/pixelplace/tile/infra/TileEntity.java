package dev.cgt.pixelplace.tile.infra;

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

@Entity
@Table(name = "tiles")
public class TileEntity {

    // tiles 테이블 매핑 엔티티
    // 이 클래스는 DB row와 Java 객체 사이의 단순 매핑만 담당하며, recovery orchestration 책임은 갖지 않음
    // tiles row는 DB 후행 저장소의 타일 snapshot
    // 실시간 authoritative state는 InMemoryTileBoard가 갖고, 이 엔티티는 복구 시작점으로 읽히는 단순 DB 매핑만 담당함
    @EmbeddedId
    private TileId id;

    // 타일의 픽셀 데이터를 1 byte/pixel 팔레트 인덱스 raw bytes로 저장하는 컬럼
    // 팔레트 인덱스 raw bytes를 그대로 저장함 타일 1개는 256 * 256 bytes가 되어야 함
    @Lob
    @Column(name = "data", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] data;

    // DB 후행 저장소에 기록된 타일 버전이며, 복구 시 메모리 타일 상태의 기준 버전으로 읽힘
    // DB에 마지막으로 flush된 타일 기준 상태 버전임 write path의 실시간 버전 원본은 메모리 타일 상태
    @Column(name = "tile_version", nullable = false)
    private long tileVersion;

    // DB가 관리하는 메타 컬럼으로, 애플리케이션이 recovery 흐름 제어용으로 갱신하지 않음
    // DB flush 시점 확인용 메타 컬럼이며, recovery orchestration 책임은 이 엔티티에 두지 않음
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected TileEntity() {
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

    @Embeddable
    public static class TileId implements Serializable {

        // (z, tx, ty) 복합키가 tiles 테이블의 타일 row 식별 기준
        // z, tx, ty 복합키는 DB tiles의 물리 row와 메모리 TileKey가 같은 기준을 쓰도록 맞춤
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
