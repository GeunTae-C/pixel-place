package dev.cgt.pixelplace.overview.scheduling;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.overview.application.OverviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * recovery runner 이후 최초 비동기 생성과 nominal 10초 fixed-delay 재생성을 연결하는 얇은 adapter
 * default Boot scheduler만 사용하며 readiness·실패·single-flight 정책은 OverviewService에 위임
 */
@Component
@Profile("!stub")
public class OverviewScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OverviewScheduler.class);

    private final OverviewService overviewService;
    private final TaskScheduler initialTaskScheduler;
    private final AtomicBoolean applicationReadyObserved = new AtomicBoolean();

    public OverviewScheduler(
            OverviewService overviewService,
            @Qualifier("taskScheduler") TaskScheduler initialTaskScheduler
    ) {
        this.overviewService = Objects.requireNonNull(
                overviewService,
                "overviewService must not be null"
        );
        this.initialTaskScheduler = Objects.requireNonNull(
                initialTaskScheduler,
                "initialTaskScheduler must not be null"
        );
    }

    /* ApplicationRunner recovery 완료 뒤 최초 refresh를 default scheduler에 제출하고 즉시 반환 */
    @EventListener(ApplicationReadyEvent.class)
    public void scheduleInitialRefresh() {
        if (!applicationReadyObserved.compareAndSet(false, true)) {
            return;
        }

        try {
            if (initialTaskScheduler.schedule(overviewService::refresh, Instant.now()) == null) {
                // 실행 future가 없으면 최초 생성이 제출됐다고 간주할 수 없음
                throw new IllegalStateException("Initial overview refresh was not scheduled.");
            }
        } catch (RuntimeException submissionFailure) {
            LOGGER.error(
                    "Failed to schedule the initial overview refresh; periodic refresh remains active.",
                    submissionFailure
            );
        }
    }

    /* context refresh부터 등록 가능한 정기 task의 ApplicationReadyEvent 선행 실행 차단 */
    @Scheduled(
            fixedDelay = BoardConstants.OVERVIEW_REFRESH_MILLIS,
            initialDelay = BoardConstants.OVERVIEW_REFRESH_MILLIS
    )
    public void runScheduledRefresh() {
        if (!applicationReadyObserved.get()) {
            return;
        }
        overviewService.refresh();
    }
}
