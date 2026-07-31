package dev.cgt.pixelplace.tile.infra;

import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.application.TileSnapshotLoader;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// stub profile에서 startup recovery tile snapshot 입력을 전체 미존재로 대체하는 adapter
// production JpaTileSnapshotLoader 미구현 대체가 아니며 DB snapshot 검증 책임은 갖지 않음
@Component
@Profile("stub")
public class StubTileSnapshotLoader implements TileSnapshotLoader {

    // DB 조회 없이 all-white pre-init 경로용 결정론적 입력 제공
    @Override
    public TileLoadResult loadZ0Tiles() {
        return TileLoadResult.allMissingResult();
    }
}
