package dev.cgt.pixelplace.tile.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

// recovery가 z=0 DB snapshot을 읽기 위해 사용하는 최소 JPA repository다.
// DB는 authoritative state가 아니라 후행 저장소이므로, 여기서 읽은 결과는 메모리 보드 복구 입력으로만 사용된다.
public interface TileJpaRepository extends JpaRepository<TileEntity, TileEntity.TileId> {

    // z=0 전체 타일을 안정적인 순서로 읽어 recovery 입력을 예측 가능하게 만든다.
    // 정렬은 ty -> tx 순서로 고정해 snapshot 변환 결과가 환경에 따라 흔들리지 않게 한다.
    @Query("""
            select t
            from TileEntity t
            where t.id.z = :z
            order by t.id.ty asc, t.id.tx asc
            """)
    List<TileEntity> findAllByZOrderByTyAscTxAsc(@Param("z") int z);
}
