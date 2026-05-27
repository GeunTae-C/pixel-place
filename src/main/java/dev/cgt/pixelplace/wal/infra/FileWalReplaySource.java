package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalRecordParser;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/*
 * 파일 기반 WAL replay source 구현체다.
 *
 * 이 클래스의 책임은 active WAL 파일을 처음부터 끝까지 순차적으로 읽고,
 * recovery 단계에서 메모리 tile state에 다시 적용해야 하는 이벤트 목록을 제공하는 것이다.
 *
 * 중요한 복구 규칙:
 *
 * 1. DB에 이미 반영된 이벤트는 다시 적용하지 않는다.
 *    - wal_checkpoint.lastFlushedEventSeq 이하의 이벤트는 DB tiles / pixel_events에 이미 반영된 상태로 간주한다.
 *    - 따라서 readAfter(lastFlushedEventSeq)는 lastFlushedEventSeq보다 큰 이벤트만 replayRecords에 담는다.
 *
 * 2. active WAL 파일은 끝까지 읽는다.
 *    - replay 대상 이벤트가 아니더라도 WAL 전체의 마지막 eventSeq를 알아야 한다.
 *    - walLastEventSeq는 DB 반영 완료 지점이 아니라 active WAL 파일에서 발견한 마지막 eventSeq다.
 *    - boot 이후 마지막 발급 eventSeq는 max(lastFlushedEventSeq, walLastEventSeq)를 기준으로 결정되어야 하기 때문이다.
 *
 * 3. WAL의 eventSeq는 반드시 strictly increasing 해야 한다.
 *    - eventSeq는 전역 이벤트 순서다.
 *    - 중복되거나 역전된 eventSeq가 발견되면, 어떤 픽셀 쓰기가 먼저인지 신뢰할 수 없다.
 *    - 이 경우 잘못된 상태로 서버를 기동하는 것보다 복구를 중단하는 것이 안전하다.
 *
 * 4. 이 구현체는 active WAL 하나만 읽는다.
 *    - MVP 기준에서는 WAL rotation / segment replay를 다루지 않는다.
 *    - 추후 WAL segment 구조가 도입되면 이 클래스 또는 별도 구현체에서 확장한다.
 */
@Primary
@Component
public class FileWalReplaySource implements WalReplaySource {

    private final WalProperties walProperties;
    private final WalRecordParser walRecordParser;

    public FileWalReplaySource(WalProperties walProperties, WalRecordParser walRecordParser) {
        this.walProperties = walProperties;
        this.walRecordParser = walRecordParser;
    }

    /*
     * lastFlushedEventSeq 이후의 WAL 이벤트만 replay 대상으로 읽는다.
     *
     * lastFlushedEventSeq는 wal_checkpoint.lastFlushedEventSeq에 저장되는 DB 반영 완료 마지막 eventSeq다.
     * 이 값 이하의 이벤트는 DB flush가 완료된 것으로 보고 다시 적용하지 않는다.
     *
     * 반환되는 walLastEventSeq는 replay 대상 여부와 무관하게
     * active WAL 전체에서 발견한 마지막 eventSeq이며, lastFlushedEventSeq와 다른 기준값이다.
     */
    @Override
    public WalReplayBatch readAfter(long lastFlushedEventSeq) {
        Path activeFile = walProperties.getActiveFile();
        // {"eventSeq":12345,"userId":7,"z":0,"tx":3,"ty":5,"x":100,"y":200,"color":17,"createdAt":"2026-04-03T06:00:00.123"}
        /*
         * 최초 기동이거나 아직 성공한 write가 없다면 WAL 파일이 없을 수 있다.
         * 이 경우 replay 대상도 없고 WAL 기준 마지막 eventSeq도 없으므로 0을 반환한다.
         */
        if (Files.notExists(activeFile)) {
            return new WalReplayBatch(List.of(), 0L);
        }

        /*
         * active WAL은 순차 읽기 가능한 일반 파일이어야 한다.
         * 디렉터리나 특수 파일이면 WAL 내구성 / replay 계약을 보장할 수 없다.
         */
        if (!Files.isRegularFile(activeFile)) {
            throw new IllegalStateException("Active WAL is not a regular file. path=" + activeFile);
        }

        List<WalRecord> replayRecords = new ArrayList<>();
        long walLastEventSeq = 0L;
        long lineNumber = 0L;

        /*
         * WAL은 JSON Lines 형식으로 한 줄씩 읽는다.
         * 각 줄의 필드 검증은 WalRecordParser가 담당하고,
         * 이 클래스는 eventSeq 순서 검증과 lastFlushedEventSeq 이후 필터링을 담당한다.
         */
        try (BufferedReader reader = Files.newBufferedReader(activeFile, StandardCharsets.UTF_8)) {
            String line;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                WalRecord record = walRecordParser.parseLine(line, lineNumber);

                /*
                 * eventSeq는 전역 이벤트 순서이므로 WAL 안에서 반드시 증가해야 한다.
                 * 중복되거나 역전되면 replay 결과와 eventSeq 발급 seed를 신뢰할 수 없다.
                 */
                if (record.eventSeq() <= walLastEventSeq) {
                    throw new IllegalStateException(
                            "WAL eventSeq is not strictly increasing. lineNumber=" + lineNumber
                                    + ", walLastEventSeq=" + walLastEventSeq
                                    + ", currentEventSeq=" + record.eventSeq()
                    );
                }

                /*
                 * replay 대상이 아니어도 마지막 eventSeq 계산에는 포함해야 한다.
                 * boot 이후 새 eventSeq는 WAL의 마지막 eventSeq 이후부터 발급되어야 한다.
                 */
                walLastEventSeq = record.eventSeq();

                /*
                 * DB 반영 완료 마지막 eventSeq 이후 이벤트만 메모리 tile state 복구 대상으로 수집한다.
                 */
                if (record.eventSeq() > lastFlushedEventSeq) {
                    replayRecords.add(record);
                }
            }
        } catch (IOException e) {
            /*
             * WAL을 읽을 수 없으면 성공 처리된 write를 복구하지 못할 수 있다.
             * 따라서 무시하지 않고 boot recovery를 중단한다.
             */
            throw new IllegalStateException("Failed to read active WAL. path=" + activeFile, e);
        }

        /*
         * walLastEventSeq는 파일을 끝까지 읽은 뒤의 WAL 마지막 eventSeq다.
         * 파일이 비어 있으면 0, 이벤트가 있으면 마지막 record의 eventSeq가 된다.
         */
        return new WalReplayBatch(replayRecords, walLastEventSeq);
    }
}
