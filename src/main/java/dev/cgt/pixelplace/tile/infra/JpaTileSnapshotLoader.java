package dev.cgt.pixelplace.tile.infra;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.application.TileSnapshotLoader;
import dev.cgt.pixelplace.tile.application.TileStateSnapshot;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

// 기본 profile의 실제 z=0 tile snapshot JPA adapter, DB 조회와 변환 경계 담당
// stub profile과 상호 배타 활성화하여 TileSnapshotLoader bean 유일성 보장
@Component
@Profile("!stub")
public class JpaTileSnapshotLoader implements TileSnapshotLoader {

    private final TileJpaRepository tileJpaRepository;

    public JpaTileSnapshotLoader(TileJpaRepository tileJpaRepository) {
        this.tileJpaRepository = tileJpaRepository;
    }

    // startup recovery용 z=0 전체 snapshot 조회, 부분 snapshot은 조용한 복구 대신 실패 처리
    @Override
    public TileLoadResult loadZ0Tiles() {
        List<TileEntity> tileEntities = tileJpaRepository.findAllByZOrderByTyAscTxAsc(BoardConstants.Z0_LEVEL);

        // DB tiles가 비어 있으면 최초 부팅 상태로 보고,
        // application 계층에서 all-white pre-init 경로를 타게 함
        if (tileEntities.isEmpty()) {
            return TileLoadResult.allMissingResult();
        }

        // DB snapshot은 전체 존재 또는 전체 미존재만 허용함
        // 일부 누락은 조용한 부분 복구 대신 recovery 실패로 다뤄야 함
        if (tileEntities.size() != BoardConstants.Z0_TILE_COUNT) {
            throw new IllegalStateException("Expected 0 or " + BoardConstants.Z0_TILE_COUNT
                    + " z=0 tile rows, but found " + tileEntities.size() + ".");
        }

        // z=0 전체 1024개 row가 모두 있으면,
        // DB 후행 저장소 snapshot을 recovery가 사용할 TileStateSnapshot 목록으로 변환함
        List<TileStateSnapshot> snapshots = tileEntities.stream()
                .map(this::toSnapshot)
                .toList();

        return TileLoadResult.fullyLoaded(snapshots);
    }

    // JPA 엔티티를 application 계층 전달 타입으로 변환해,
    // recovery 서비스가 DB/JPA 세부사항을 직접 모르도록 경계를 유지함
    private TileStateSnapshot toSnapshot(TileEntity tileEntity) {
        return new TileStateSnapshot(
                new TileKey(tileEntity.getZ(), tileEntity.getTx(), tileEntity.getTy()),
                tileEntity.getData(),
                tileEntity.getTileVersion()
        );
    }
}
