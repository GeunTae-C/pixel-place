package dev.cgt.pixelplace.recovery.application;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.application.TileSnapshotLoader;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/* proxied capture 경계의 조회 순서, 조립 결과와 명시적 transaction 계약 검증 */
class StartupRecoveryDbViewCaptureServiceTest {

    @Test
    void checkpointIsReadBeforeTilesAndBothResultsShareOneView() {
        List<String> calls = new ArrayList<>();
        CheckpointSnapshot checkpoint = new CheckpointSnapshot(3L);
        TileLoadResult tiles = TileLoadResult.allMissingResult();
        CheckpointReader checkpointReader = () -> {
            calls.add("checkpoint");
            return checkpoint;
        };
        TileSnapshotLoader tileLoader = () -> {
            calls.add("tiles");
            return tiles;
        };
        StartupRecoveryDbViewCaptureService service =
                new StartupRecoveryDbViewCaptureService(checkpointReader, tileLoader);

        StartupRecoveryDbView view = service.capture();

        assertEquals(List.of("checkpoint", "tiles"), calls);
        assertSame(checkpoint, view.checkpoint());
        assertSame(tiles, view.tileLoadResult());
    }

    @Test
    void checkpointFailurePreventsTileRead() {
        IllegalStateException failure = new IllegalStateException("checkpoint missing");
        AtomicInteger tileCalls = new AtomicInteger();
        StartupRecoveryDbViewCaptureService service = new StartupRecoveryDbViewCaptureService(
                () -> {
                    throw failure;
                },
                () -> {
                    tileCalls.incrementAndGet();
                    return TileLoadResult.allMissingResult();
                }
        );

        IllegalStateException actual = assertThrows(IllegalStateException.class, service::capture);

        assertSame(failure, actual);
        assertEquals(0, tileCalls.get());
    }

    @Test
    void captureDeclaresReadOnlyRepeatableReadRequiredTransaction() throws Exception {
        Method method = StartupRecoveryDbViewCaptureService.class.getMethod("capture");
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertEquals(true, transactional.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, transactional.isolation());
        assertEquals(Propagation.REQUIRED, transactional.propagation());
    }
}
