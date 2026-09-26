package dev.cgt.pixelplace.overview.application;

import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/*
 * 마지막 정상 Overview PNG의 process-local 원자 게시와 generation single-flight 담당
 * generation 실패는 마지막 이미지와 global readiness를 건드리지 않고 다음 주기 재시도로 격리
 */
@Service
public class OverviewService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OverviewService.class);

    private final OverviewRenderer overviewRenderer;
    private final PixelMeasurement measurement;
    private final ServiceReadiness serviceReadiness;
    private final AtomicReference<byte[]> currentPng = new AtomicReference<>();
    private final AtomicBoolean generationInProgress = new AtomicBoolean();

    public OverviewService(
            OverviewRenderer overviewRenderer,
            ServiceReadiness serviceReadiness,
            PixelMeasurement measurement
    ) {
        this.measurement = measurement;
        this.overviewRenderer = Objects.requireNonNull(
                overviewRenderer,
                "overviewRenderer must not be null"
        );
        this.serviceReadiness = Objects.requireNonNull(
                serviceReadiness,
                "serviceReadiness must not be null"
        );
    }

    /*
     * ready 상태에서만 non-blocking single-flight로 완전한 PNG 하나 생성 시도
     * RuntimeException은 기록 후 정상 반환하며 기존 정상 이미지와 다른 subsystem 상태 보존
     */
    public void refresh() {
        var scope = measurement.begin(PixelMeasurement.Operation.overview);
        if (!serviceReadiness.isReady()) {
            measurement.end(scope, PixelMeasurement.Outcome.skipped);
            return;
        }
        if (!generationInProgress.compareAndSet(false, true)) {
            measurement.end(scope, PixelMeasurement.Outcome.skipped);
            return;
        }

        Throwable failure = null;
        try {
            byte[] renderedPng = overviewRenderer.render();
            if (renderedPng == null || renderedPng.length == 0) {
                // renderer 계약 위반 결과를 게시하면 concurrent HTTP reader가 가짜 PNG를 관측함
                throw new IllegalStateException("Overview renderer returned no PNG bytes.");
            }
            currentPng.set(renderedPng);
        } catch (RuntimeException generationFailure) {
            failure = generationFailure;
            try {
                LOGGER.error("Overview PNG generation failed; keeping the last successful image.", generationFailure);
            } catch (Error loggingError) { failure = loggingError; throw loggingError; }
        } catch (Error error) { failure = error; throw error;
        } finally {
            generationInProgress.set(false);
            measurement.endPreserving(scope, failure == null ? PixelMeasurement.Outcome.success : PixelMeasurement.Outcome.failure, failure);
        }
    }

    /* 현재 HTTP reader에 완전히 게시된 마지막 정상 PNG 하나만 노출 */
    public Optional<byte[]> currentPng() {
        return Optional.ofNullable(currentPng.get());
    }
}
