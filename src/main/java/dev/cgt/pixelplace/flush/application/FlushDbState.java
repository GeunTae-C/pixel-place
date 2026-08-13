package dev.cgt.pixelplace.flush.application;

import java.util.Objects;

/* reconciliation probe가 한 transaction에서 관측한 exact checkpoint와 bootstrap state */
public record FlushDbState(long lastFlushedEventSeq, DbBootstrapState bootstrapState) {

    public FlushDbState {
        if (lastFlushedEventSeq < 0) {
            throw new IllegalArgumentException("lastFlushedEventSeq must not be negative");
        }
        Objects.requireNonNull(bootstrapState, "bootstrapState must not be null");
    }
}
