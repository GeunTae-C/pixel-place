package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;

/** 요청별 동기 실행과 종료 소진 경계. lifecycle mutex를 보유한 채 write 잠금 대기 금지 */
public final class SinglePixelWriteExecutor implements PixelWriteExecutor {
    private final FlushBoundaryCoordinator coordinator;
    private final PixelWriteService core;
    private final DirtyTileTracker dirty;
    private final ServiceReadiness readiness;
    private final PixelMeasurement measurement;
    private final Object lifecycle = new Object();
    private boolean stopping;
    private boolean closed;
    private int active;

    public SinglePixelWriteExecutor(FlushBoundaryCoordinator coordinator, PixelWriteService core,
                                   DirtyTileTracker dirty, ServiceReadiness readiness, PixelMeasurement measurement) {
        this.coordinator = coordinator;
        this.core = core;
        this.dirty = dirty;
        this.readiness = readiness;
        this.measurement = measurement;
    }

    /** 등록이 close보다 앞선 요청은 정상 처리 시도. boundary 대기부터 dirty 반환까지 계수 */
    @Override
    public PixelWriteResult execute(long userId, int x, int y, int color) {
        synchronized (lifecycle) {
            if (stopping) throw new ServiceNotReadyException();
            active++;
        }
        try {
            return coordinator.coordinate(() -> {
                try {
                    PixelWriteResult result = measurement.observe(PixelMeasurement.Operation.core_call,
                            () -> core.writePixel(userId, x, y, color));
                    try {
                        dirty.markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());
                    } catch (RuntimeException failure) {
                        // memory 성공 뒤 dirty 실패는 해당 요청 500. 후속 write 및 WAL 기반 flush 유지
                        throw new IllegalStateException("Dirty tile mark failed after successful write. eventSeq="
                                + result.eventSeq(), failure);
                    }
                    return result;
                } catch (Error fatal) {
                    // boundary 반환 전에 새 capture 차단. 원래 Error와 suppressed 유지
                    readiness.markWriteFailed();
                    throw fatal;
                }
            });
        } finally {
            synchronized (lifecycle) {
                active--;
                lifecycle.notifyAll();
            }
        }
    }

    @Override
    public ExecutionSnapshot snapshot() {
        synchronized (lifecycle) {
            return new ExecutionSnapshot(0, active, 0, active, 0,
                    closed ? ExecutionSnapshot.State.STOPPED : stopping ? ExecutionSnapshot.State.STOPPING
                            : ExecutionSnapshot.State.RUNNING, active, false);
        }
    }

    /** 이미 등록한 요청을 모두 소진한 뒤 반환. interrupt로 storage 선행 종료를 허용하지 않음 */
    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (lifecycle) {
            stopping = true;
            while (active != 0) {
                try { lifecycle.wait(); }
                catch (InterruptedException interruption) { interrupted = true; }
            }
            closed = true;
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
