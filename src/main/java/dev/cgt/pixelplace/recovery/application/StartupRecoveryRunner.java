package dev.cgt.pixelplace.recovery.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// 애플리케이션 시작 시 recovery를 한 번 실행하는 부트 훅이다.
// recovery 실행 시점을 한 곳으로 고정해 이후 실제 구현이 들어와도 부팅 흐름이 흔들리지 않게 한다.
@Component
public class StartupRecoveryRunner implements ApplicationRunner {

    private final StartupRecoveryService startupRecoveryService;

    public StartupRecoveryRunner(StartupRecoveryService startupRecoveryService) {
        this.startupRecoveryService = startupRecoveryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        startupRecoveryService.recover();
    }
}
