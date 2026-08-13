package dev.cgt.pixelplace.pixel.infra;

import dev.cgt.pixelplace.pixel.application.PixelEventWriter;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/*
 * immutable WAL record 목록을 append-only entity로 변환하고 한 번의 saveAll로 저장하는 adapter
 * explicit flush로 duplicate PK와 SQL constraint 실패를 transaction commit 이전 body 실패로 노출
 */
@Component
@Profile("!stub")
public class JpaPixelEventWriter implements PixelEventWriter {

    private final PixelEventJpaRepository pixelEventJpaRepository;

    public JpaPixelEventWriter(PixelEventJpaRepository pixelEventJpaRepository) {
        this.pixelEventJpaRepository = pixelEventJpaRepository;
    }

    @Override
    public void appendAll(List<WalRecord> walRecords) {
        requireRecords(walRecords);
        List<PixelEventEntity> entities = walRecords.stream()
                .map(PixelEventEntity::fromWalRecord)
                .toList();

        pixelEventJpaRepository.saveAll(entities);
        pixelEventJpaRepository.flush();
    }

    private void requireRecords(List<WalRecord> walRecords) {
        Objects.requireNonNull(walRecords, "walRecords must not be null");
        if (walRecords.isEmpty()) {
            throw new IllegalArgumentException("walRecords must not be empty");
        }
        for (WalRecord record : walRecords) {
            Objects.requireNonNull(record, "walRecords must not contain null");
        }
    }
}
