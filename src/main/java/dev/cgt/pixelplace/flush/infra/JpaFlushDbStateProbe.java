package dev.cgt.pixelplace.flush.infra;

import dev.cgt.pixelplace.checkpoint.application.CheckpointFence;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.flush.application.DbBootstrapState;
import dev.cgt.pixelplace.flush.application.FlushDbState;
import dev.cgt.pixelplace.flush.application.FlushDbStateProbe;
import dev.cgt.pixelplace.tile.application.TileMetadataReader;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/*
 * ambiguous transaction의 main row lock 해제를 기다린 뒤 checkpoint와 tile mode를 함께 읽는 JPA probe
 * FOR UPDATE가 필요하므로 read-only hint 없이 별도 REQUIRES_NEW transaction에서 일관된 상태 관측
 */
@Component
@Profile("!stub")
public class JpaFlushDbStateProbe implements FlushDbStateProbe {

    static final String TRANSACTION_NAME = "pixel-place-flush-reconciliation-probe";

    private final CheckpointFence checkpointFence;
    private final TileMetadataReader tileMetadataReader;
    private final DbBootstrapClassifier dbBootstrapClassifier;
    private final TransactionTemplate transactionTemplate;

    public JpaFlushDbStateProbe(
            PlatformTransactionManager transactionManager,
            CheckpointFence checkpointFence,
            TileMetadataReader tileMetadataReader,
            DbBootstrapClassifier dbBootstrapClassifier
    ) {
        this.checkpointFence = checkpointFence;
        this.tileMetadataReader = tileMetadataReader;
        this.dbBootstrapClassifier = dbBootstrapClassifier;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setName(TRANSACTION_NAME);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        this.transactionTemplate.setReadOnly(false);
    }

    @Override
    public FlushDbState probe() {
        return Objects.requireNonNull(
                transactionTemplate.execute(status -> probeWithinTransaction()),
                "reconciliation probe transaction returned null"
        );
    }

    private FlushDbState probeWithinTransaction() {
        long checkpoint = checkpointFence.lockMainCheckpoint();
        List<TileKey> tileKeys = tileMetadataReader.readAllTileKeys();
        DbBootstrapState state = dbBootstrapClassifier.classify(checkpoint, tileKeys);
        if (state == DbBootstrapState.INCONSISTENT) {
            // partial tile shape를 rollback 또는 commit 증거로 추측하지 않음
            throw new IllegalStateException("Reconciliation observed inconsistent DB bootstrap state.");
        }
        return new FlushDbState(checkpoint, state);
    }
}
