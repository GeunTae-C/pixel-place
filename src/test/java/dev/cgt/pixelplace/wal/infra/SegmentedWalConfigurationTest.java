package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import dev.cgt.pixelplace.wal.application.WalRecordParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 설정 실패의 startup 차단과 외부 property 변경으로 인한 파일군 이동 방지 검증 */
class SegmentedWalConfigurationTest {
    @TempDir Path directory;
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test
    void defaultsAndCustomPositiveSizeBindWithoutCreatingFiles() throws Exception {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(33554432L, context.getBean(WalProperties.class).getMaxSegmentBytes());
        });
        runner.withPropertyValues("pixel-place.wal.max-segment-bytes=1", "pixel-place.wal.active-file=" + directory.resolve("missing/wal"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1L, context.getBean(WalProperties.class).getMaxSegmentBytes());
                    assertFalse(Files.exists(directory.resolve("missing")));
                });
        assertTrue(Files.readString(Path.of("src/main/resources/application.yml")).contains("max-segment-bytes: 33554432"));
    }

    @ParameterizedTest
    @ValueSource(strings={"", " ", "0", "-1", "9223372036854775808", "size"})
    void invalidSizeFailsStartupWithoutFallback(String value) {
        runner.withPropertyValues("pixel-place.wal.max-segment-bytes=" + value)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void nullEmptyAndRootPathsAreRejectedWithoutIo() {
        for (Path path : new Path[]{null, Path.of(""), directory.toAbsolutePath().getRoot()}) {
            WalProperties properties = new WalProperties();
            properties.setActiveFile(path);
            assertThrows(IllegalArgumentException.class, properties::validate);
            assertThrows(IllegalArgumentException.class, () -> new SegmentedWalStorage(properties, mock(WalRecordParser.class), mock(WalRecordJsonCodec.class), dev.cgt.pixelplace.measurement.Measurements.disabled()));
        }
        runner.withPropertyValues("pixel-place.wal.active-file=")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void constructorFreezesNormalizedPathWithoutFileAccess() {
        WalProperties properties = new WalProperties();
        Path input = directory.resolve("missing/../custom/wal");
        properties.setActiveFile(input);
        SegmentedWalStorage storage = new SegmentedWalStorage(properties, mock(WalRecordParser.class), mock(WalRecordJsonCodec.class), dev.cgt.pixelplace.measurement.Measurements.disabled());
        properties.setActiveFile(directory.resolve("other"));
        properties.setMaxSegmentBytes(1);
        assertEquals(input.toAbsolutePath().normalize(), storage.segmentPath(0));
        assertFalse(Files.exists(directory.resolve("custom")));
    }

    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(WalProperties.class)
    static class Binding { }
}
