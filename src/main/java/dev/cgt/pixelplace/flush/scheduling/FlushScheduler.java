package dev.cgt.pixelplace.flush.scheduling;

import dev.cgt.pixelplace.flush.application.AmbiguousFlushCommitException;
import dev.cgt.pixelplace.flush.application.FlushFailClosedException;
import dev.cgt.pixelplace.flush.application.FlushPersistenceRolledBackException;
import dev.cgt.pixelplace.flush.application.FlushRunResult;
import dev.cgt.pixelplace.flush.application.FlushWorker;
import dev.cgt.pixelplace.flush.application.UnresolvedFlushCommitException;
import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/* fixed-delay invocation 하나를 FlushWorker whole-cycle 호출 하나로 연결하는 얇은 adapter */
@Component
@Profile("!stub")
public class FlushScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FlushScheduler.class);

    private final FlushWorker flushWorker;

    public FlushScheduler(FlushWorker flushWorker) {
        this.flushWorker = flushWorker;
    }

    /* named 전용 scheduler에서 worker를 정확히 한 번 호출하고 runtime failure 뒤 다음 주기 유지 */
    @Scheduled(
            scheduler = "flushTaskScheduler",
            fixedDelayString = "${pixel-place.flush.fixed-delay}",
            initialDelayString = "${pixel-place.flush.fixed-delay}"
    )
    public void runScheduledFlush() {
        try {
            logResult(flushWorker.flushOnce());
        } catch (ServiceNotReadyException notReady) {
            LOGGER.debug("Scheduled flush skipped because the service is not ready.", notReady);
        } catch (FlushPersistenceRolledBackException rolledBack) {
            LOGGER.error("Scheduled flush transaction rolled back.", rolledBack);
        } catch (AmbiguousFlushCommitException ambiguousCommit) {
            LOGGER.error("Scheduled flush commit outcome is ambiguous.", ambiguousCommit);
        } catch (UnresolvedFlushCommitException unresolvedCommit) {
            LOGGER.error("Scheduled flush reconciliation remains unresolved.", unresolvedCommit);
        } catch (FlushFailClosedException failClosed) {
            LOGGER.error("Scheduled flush entered irreversible fatal-not-ready state.", failClosed);
        } catch (RuntimeException unexpectedFailure) {
            LOGGER.error("Unexpected scheduled flush failure.", unexpectedFailure);
        }
    }

    private void logResult(FlushRunResult result) {
        switch (result) {
            case SKIPPED_ALREADY_RUNNING ->
                    LOGGER.debug("Scheduled flush skipped because another cycle is running.");
            case NO_OP -> LOGGER.debug("Scheduled flush found no WAL records to persist.");
            case COMMITTED -> LOGGER.info("Scheduled flush committed.");
            case RECONCILED_COMMIT -> LOGGER.info("Scheduled flush reconciled a committed transaction.");
            case RECONCILED_ROLLBACK ->
                    LOGGER.warn("Scheduled flush reconciled a rollback and restored dirty tiles.");
        }
    }
}
