package dev.cgt.pixelplace.tile.infra;

import dev.cgt.pixelplace.flush.application.FlushTileSnapshot;
import dev.cgt.pixelplace.tile.application.TileSnapshotWriter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/*
 * captured snapshot만 tiles entity로 변환하는 default profile adapter
 * explicit flush로 BLOB/constraint 실패를 checkpoint advance 이전 transaction body에서 노출
 */
@Component
@Profile("!stub")
public class JpaTileSnapshotWriter implements TileSnapshotWriter {

    private final TileJpaRepository tileJpaRepository;

    public JpaTileSnapshotWriter(TileJpaRepository tileJpaRepository) {
        this.tileJpaRepository = tileJpaRepository;
    }

    /* caller transaction 안에서 immutable plan snapshot만 entity로 바꾸고 SQL 실패까지 즉시 노출 */
    @Override
    public void writeAll(List<FlushTileSnapshot> tileSnapshots) {
        requireSnapshots(tileSnapshots);
        List<TileEntity> entities = tileSnapshots.stream()
                .map(TileEntity::fromFlushSnapshot)
                .toList();

        tileJpaRepository.saveAll(entities);
        tileJpaRepository.flush();
    }

    private void requireSnapshots(List<FlushTileSnapshot> tileSnapshots) {
        Objects.requireNonNull(tileSnapshots, "tileSnapshots must not be null");
        if (tileSnapshots.isEmpty()) {
            throw new IllegalArgumentException("tileSnapshots must not be empty");
        }
        for (FlushTileSnapshot snapshot : tileSnapshots) {
            Objects.requireNonNull(snapshot, "tileSnapshots must not contain null");
        }
    }
}
