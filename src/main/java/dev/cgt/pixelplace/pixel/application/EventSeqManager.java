package dev.cgt.pixelplace.pixel.application;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/*
 * 서버가 직접 발급하는 eventSeq의 단일 관리 지점이다.
 *
 * recovery seed는 이미 DB 또는 WAL에 존재하는 마지막 eventSeq로 취급한다.
 * 따라서 initializeLastIssued(seed)는 다음 발급값이 아니라 마지막 발급 완료값을 저장하고,
 * allocate()는 항상 그 다음 번호(seed + 1)부터 반환해야 한다.
 */
@Component
public class EventSeqManager {

    private final AtomicLong lastIssuedEventSeq = new AtomicLong(0L);

    /*
     * boot recovery가 끝나기 전에 마지막 발급 완료 eventSeq를 주입한다.
     * seed는 max(lastFlushedEventSeq, walLastEventSeq)여야 하며, 음수 seed는 복구 기준점이 깨진 상태이므로 거부한다.
     */
    public void initializeLastIssued(long seed) {
        if (seed < 0) {
            // eventSeq는 1부터 증가하는 정합성 기준이므로 음수 기준점으로 서버를 열 수 없다.
            throw new IllegalArgumentException("eventSeq seed must not be negative. seed=" + seed);
        }
        lastIssuedEventSeq.set(seed);
    }

    /*
     * write path가 새 이벤트를 승인하기 직전에 호출할 발급 메서드다.
     * 반환값은 이전에 발급된 마지막 eventSeq보다 정확히 1 큰 값이어야 한다.
     */
    public long allocate() {
        return lastIssuedEventSeq.incrementAndGet();
    }

    /*
     * recovery 검증과 운영 관측에서 현재까지 발급 완료된 마지막 eventSeq를 확인한다.
     * 이 값은 다음 발급값이 아니라 마지막 발급 완료값이다.
     */
    public long currentLastIssued() {
        return lastIssuedEventSeq.get();
    }
}
