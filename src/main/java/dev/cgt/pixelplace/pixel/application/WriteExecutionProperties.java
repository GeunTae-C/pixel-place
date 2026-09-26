package dev.cgt.pixelplace.pixel.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** 실행 전략의 입력 검증. single에서도 명시된 group 설정 오류를 숨기지 않음 */
@ConfigurationProperties(prefix = "pixel-place.write", ignoreUnknownFields = false)
public class WriteExecutionProperties {
    private String mode = "group";
    private final Group group = new Group();
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Group getGroup() { return group; }

    /** 파일 접근 없이 모든 범위와 size/capacity 관계 확인 */
    public void validate() {
        if (!"single".equals(mode) && !"group".equals(mode)) throw new IllegalArgumentException("Unknown write mode");
        if (group.maxBatchSize < 1 || group.maxBatchSize > 128
                || group.maxOutstanding < 1 || group.maxOutstanding > 4096
                || group.maxBatchSize > group.maxOutstanding) throw new IllegalArgumentException("Invalid write group capacity");
        positiveBound(group.queueTimeout, Duration.ofSeconds(30));
        positiveBound(group.shutdownGrace, Duration.ofSeconds(60));
    }

    private static void positiveBound(Duration value, Duration maximum) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0)
            throw new IllegalArgumentException("Invalid write group duration");
    }

    /** group의 유한 접수·종료 입력. 추가 수집 대기 설정은 없음 */
    public static class Group {
        private int maxBatchSize = 16;
        private int maxOutstanding = 128;
        private Duration queueTimeout = Duration.ofSeconds(1);
        private Duration shutdownGrace = Duration.ofSeconds(10);
        public int getMaxBatchSize() { return maxBatchSize; }
        public void setMaxBatchSize(int value) { maxBatchSize = value; }
        public int getMaxOutstanding() { return maxOutstanding; }
        public void setMaxOutstanding(int value) { maxOutstanding = value; }
        public Duration getQueueTimeout() { return queueTimeout; }
        public void setQueueTimeout(Duration value) { queueTimeout = value; }
        public Duration getShutdownGrace() { return shutdownGrace; }
        public void setShutdownGrace(Duration value) { shutdownGrace = value; }
    }
}
