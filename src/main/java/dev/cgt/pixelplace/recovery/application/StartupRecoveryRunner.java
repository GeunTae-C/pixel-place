package dev.cgt.pixelplace.recovery.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// application context 기동 뒤 StartupRecoveryService를 정확히 한 번 호출하는 부트 훅
// DB view 검증·WAL replay·readiness 전환 책임은 갖지 않고 실행 시점만 연결
@Component
public class StartupRecoveryRunner implements ApplicationRunner {

    private final StartupRecoveryService startupRecoveryService;

    public StartupRecoveryRunner(StartupRecoveryService startupRecoveryService) {
        this.startupRecoveryService = startupRecoveryService;
    }

    /* 보호 API가 열리기 전 startup recovery 전체 흐름 시작 */
    @Override
    public void run(ApplicationArguments args) {
        startupRecoveryService.recover();
    }
}
