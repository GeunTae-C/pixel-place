package dev.cgt.pixelplace.tile.infra;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.application.TileSnapshotLoader;
import dev.cgt.pixelplace.tile.application.TileStateSnapshot;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /* capture transaction에 참여하여 전체 key metadata 뒤 z=0 bytes를 같은 DB snapshot에서 조회 */
    @Override
    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ,
            propagation = Propagation.REQUIRED
    )
    public TileLoadResult loadZ0Tiles() {
        List<TileKey> databaseTileKeys = tileJpaRepository.findAllTileKeysOrderByZTyTx();
        List<TileEntity> tileEntities = tileJpaRepository.findAllByZOrderByTyAscTxAsc(BoardConstants.Z0_LEVEL);
        List<TileStateSnapshot> snapshots = tileEntities.stream()
                .map(this::toSnapshot)
                .toList();

        return new TileLoadResult(databaseTileKeys, snapshots);
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
