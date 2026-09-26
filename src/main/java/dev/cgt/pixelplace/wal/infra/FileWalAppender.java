package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalAppender;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 기존 write port의 bean 역할 유지. 채널·상태·lock·Spring 종료 소유권은 공유 storage에 한정 */
@Primary
@Component
public class FileWalAppender implements WalAppender {
    private final SegmentedWalStorage storage;
    private final PixelMeasurement measurement;

    public FileWalAppender(SegmentedWalStorage storage, PixelMeasurement measurement) {
        this.storage = storage;
        this.measurement = measurement;
    }

    /** storage의 record force(true) 완료까지 대기하는 core write 내구성 경계 */
    @Override
    public void appendAndFsync(WalRecord record) {
        measurement.observe(PixelMeasurement.Operation.append, () -> storage.appendAndFsync(record));
    }

    /** 같은 공유 storage의 파일별 force 경계로 전체 batch 전달 */
    @Override
    public void appendBatchAndFsync(List<WalRecord> records) {
        measurement.observe(PixelMeasurement.Operation.append, () -> storage.appendBatchAndFsync(records));
    }

    /** 직접 생성 runtime fixture의 종료 호환용 위임. Spring destroy owner는 storage 하나 */
    public void close() {
        storage.close();
    }
}
