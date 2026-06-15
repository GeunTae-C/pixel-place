package dev.cgt.pixelplace.wal.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/*
 * WAL 파일 위치 설정을 Spring 설정 바인딩으로 받는 객체
 * active WAL은 승인된 write의 1차 내구성 원본이므로, recovery와 write path가 같은 파일을 바라보게 만드는 설정 경계
 */
@Component
@ConfigurationProperties(prefix = "pixel-place.wal")
public class WalProperties {

    private Path activeFile = Path.of("./data/wal/pixel-place.wal");

    public Path getActiveFile() {
        return activeFile;
    }

    public void setActiveFile(Path activeFile) {
        this.activeFile = activeFile;
    }
}
