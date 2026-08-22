package dev.cgt.pixelplace.flush.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ErrorHandler;

import java.util.Objects;

/* 반복 flush task의 raw Error 관측과 동일 instance 재전파 경계 담당 */
final class FlushSchedulingErrorHandler implements ErrorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FlushSchedulingErrorHandler.class);

    private final ErrorLogSink errorLogSink;

    FlushSchedulingErrorHandler() {
        this(failure -> LOGGER.error("Unhandled failure escaped the scheduled flush boundary.", failure));
    }

    FlushSchedulingErrorHandler(ErrorLogSink errorLogSink) {
        this.errorLogSink = Objects.requireNonNull(errorLogSink, "errorLogSink must not be null");
    }

    /* Error는 반복 task 중단을 위해 보존·재전파, non-Error는 scheduling continuity 유지 */
    @Override
    public void handleError(Throwable failure) {
        if (failure instanceof Error error) {
            try {
                errorLogSink.log(error);
            } catch (Throwable loggingFailure) {
                addSuppressedByIdentity(error, loggingFailure);
            }
            throw error;
        }

        try {
            errorLogSink.log(failure);
        } catch (RuntimeException ignored) {
            // non-Error 관측 실패는 반복 task continuity보다 우선하지 않음
        } catch (Error loggingError) {
            // logging 자체의 raw Error는 infrastructure failure로 숨기지 않음
            throw loggingError;
        }
    }

    private void addSuppressedByIdentity(Error primary, Throwable loggingFailure) {
        if (loggingFailure == primary) {
            return;
        }
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == loggingFailure) {
                return;
            }
        }

        try {
            primary.addSuppressed(loggingFailure);
        } catch (Throwable ignored) {
            // suppression 비활성화 여부와 무관하게 원래 Error identity 재전파가 우선
        }
    }

    @FunctionalInterface
    interface ErrorLogSink {

        void log(Throwable failure);
    }
}
