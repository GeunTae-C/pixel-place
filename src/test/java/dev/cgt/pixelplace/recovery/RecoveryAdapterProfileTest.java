package dev.cgt.pixelplace.recovery;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.infra.JpaCheckpointReader;
import dev.cgt.pixelplace.checkpoint.infra.StubCheckpointReader;
import dev.cgt.pixelplace.checkpoint.infra.WalCheckpointJpaRepository;
import dev.cgt.pixelplace.tile.application.TileSnapshotLoader;
import dev.cgt.pixelplace.tile.infra.JpaTileSnapshotLoader;
import dev.cgt.pixelplace.tile.infra.StubTileSnapshotLoader;
import dev.cgt.pixelplace.tile.infra.TileJpaRepository;
import dev.cgt.pixelplace.wal.application.WalRecordParser;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import dev.cgt.pixelplace.wal.infra.FileWalReplaySource;
import dev.cgt.pixelplace.wal.infra.StubWalReplaySource;
import dev.cgt.pixelplace.wal.infra.WalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

// 외부 DB/WAL 호출 없이 startup recovery 입력 adapter의 profile별 bean 유일성 검증
class RecoveryAdapterProfileTest {

    @Test
    void defaultProfileSelectsExactlyOneRealAdapterForEachRecoveryPort() {
        try (AnnotationConfigApplicationContext context = openContext()) {
            assertSingleBean(context, CheckpointReader.class, JpaCheckpointReader.class);
            assertSingleBean(context, TileSnapshotLoader.class, JpaTileSnapshotLoader.class);
            assertSingleBean(context, WalReplaySource.class, FileWalReplaySource.class);
        }
    }

    @Test
    void stubProfileSelectsExactlyOneStubAdapterForEachRecoveryPort() {
        try (AnnotationConfigApplicationContext context = openContext("stub")) {
            assertSingleBean(context, CheckpointReader.class, StubCheckpointReader.class);
            assertSingleBean(context, TileSnapshotLoader.class, StubTileSnapshotLoader.class);
            assertSingleBean(context, WalReplaySource.class, StubWalReplaySource.class);
        }
    }

    private AnnotationConfigApplicationContext openContext(String... activeProfiles) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(activeProfiles);

        // real adapter 생성 의존성만 제공하며 repository/WAL 메서드는 호출하지 않음
        context.registerBean(
                WalCheckpointJpaRepository.class,
                () -> mock(WalCheckpointJpaRepository.class)
        );
        context.registerBean(TileJpaRepository.class, () -> mock(TileJpaRepository.class));
        context.registerBean(WalProperties.class, WalProperties::new);
        context.registerBean(WalRecordParser.class, () -> mock(WalRecordParser.class));

        context.register(
                JpaCheckpointReader.class,
                StubCheckpointReader.class,
                JpaTileSnapshotLoader.class,
                StubTileSnapshotLoader.class,
                FileWalReplaySource.class,
                StubWalReplaySource.class
        );
        context.refresh();
        return context;
    }

    private <T> void assertSingleBean(
            AnnotationConfigApplicationContext context,
            Class<T> portType,
            Class<? extends T> expectedImplementation
    ) {
        Map<String, T> beans = context.getBeansOfType(portType);

        assertEquals(1, beans.size());
        assertEquals(expectedImplementation, context.getBean(portType).getClass());
    }
}
