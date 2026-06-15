package dev.cgt.pixelplace.tile.infra;

import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.application.TileSnapshotLoader;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 실제 DB 전체 로더가 아직 없으므로, 현재는 "DB tiles 전체 미존재" 기본 경로를 재현하는 stub
// 이후 recovery가 실제 DB snapshot을 읽도록 교체될 자리
@Profile("stub")
// 실제 인프라를 붙이지 않는 테스트/개발 profile에서만 사용하는 임시 stub
@Component
public class StubTileSnapshotLoader implements TileSnapshotLoader {

    @Override
    public TileLoadResult loadZ0Tiles() {
        return TileLoadResult.allMissingResult();
    }
}
