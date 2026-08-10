package dev.cgt.pixelplace.tile.infra;

import dev.cgt.pixelplace.tile.domain.TileKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

// recovery snapshot과 runtime bootstrap key metadata 조회를 분리해 제공하는 tiles JPA repository
// DB는 authoritative state가 아니라 후행 저장소이며 runtime 판정에서 BLOB을 불필요하게 읽으면 안 됨
public interface TileJpaRepository extends JpaRepository<TileEntity, TileEntity.TileId> {

    // z=0 전체 타일을 안정적인 순서로 읽어 recovery 입력을 예측 가능하게 만듦
    // 정렬은 ty -> tx 순서로 고정해 snapshot 변환 결과가 환경에 따라 흔들리지 않게 함
    @Query("""
            select t
            from TileEntity t
            where t.id.z = :z
            order by t.id.ty asc, t.id.tx asc
            """)
    List<TileEntity> findAllByZOrderByTyAscTxAsc(@Param("z") int z);

    // z 범위 밖 row까지 bootstrap 불일치로 발견하기 위한 전체 key constructor projection
    @Query("""
            select new dev.cgt.pixelplace.tile.domain.TileKey(t.id.z, t.id.tx, t.id.ty)
            from TileEntity t
            order by t.id.z asc, t.id.ty asc, t.id.tx asc
            """)
    List<TileKey> findAllTileKeysOrderByZTyTx();
}
