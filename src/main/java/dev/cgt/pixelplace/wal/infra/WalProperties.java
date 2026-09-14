package dev.cgt.pixelplace.wal.infra;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/*
 * 기존 WAL 기준 경로와 record 단위 회전 한도의 시작 시 검증 경계
 * storage 생성 시 값을 고정하므로 실행 중 외부 property 변경으로 파일군 전환 금지
 */
@Component
@ConfigurationProperties(prefix = "pixel-place.wal")
public class WalProperties implements EnvironmentAware {

    private Path activeFile = Path.of("./data/wal/pixel-place.wal");
    private long maxSegmentBytes = 33554432L;
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public Path getActiveFile() {
        return activeFile;
    }

    public void setActiveFile(Path activeFile) {
        this.activeFile = activeFile;
    }

    public long getMaxSegmentBytes() {
        return maxSegmentBytes;
    }

    public void setMaxSegmentBytes(long maxSegmentBytes) {
        this.maxSegmentBytes = maxSegmentBytes;
    }

    /** 파일 I/O 없는 startup 검증. 직접 생성 fixture에도 동일 계약 적용 */
    @PostConstruct
    public void validate() {
        String configuredPath = environment == null ? null : environment.getProperty("pixel-place.wal.active-file");
        if (configuredPath != null && configuredPath.isBlank()) {
            // Spring의 빈 Path 변환 생략이 기본 경로 fallback으로 이어지는 것을 차단
            throw new IllegalArgumentException("WAL active-file must not be blank");
        }
        normalizedBasePath();
        if (maxSegmentBytes <= 0) {
            // 기본값 fallback은 잘못된 용량 설정을 숨기므로 거부
            throw new IllegalArgumentException("WAL max-segment-bytes must be positive");
        }
    }

    Path normalizedBasePath() {
        if (activeFile == null || activeFile.toString().isBlank()) {
            throw new IllegalArgumentException("WAL active-file must have a file name");
        }
        Path normalized = activeFile.toAbsolutePath().normalize();
        if (normalized.getFileName() == null) {
            // root는 파일군 기준 이름을 제공하지 못함
            throw new IllegalArgumentException("WAL active-file must not be a root path");
        }
        return normalized;
    }
}
