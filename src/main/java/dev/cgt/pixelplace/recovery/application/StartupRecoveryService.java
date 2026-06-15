package dev.cgt.pixelplace.recovery.application;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.application.TileSnapshotLoader;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.springframework.stereotype.Service;

// startup recovery 전체 순서를 강제하는 핵심 오케스트레이터
// 메모리 타일 보드를 authoritative state로 세우기 위해 checkpoint, DB snapshot, WAL replay를 정해진 순서로만 연결함
@Service
public class StartupRecoveryService {

    /*
        CheckpointReader
        = DB가 어디까지 WAL을 flush했는지 읽는 포트

        TileSnapshotLoader
        = DB tiles snapshot을 읽는 포트

        WalReplaySource
        = WAL 파일에서 replay 대상 이벤트를 읽는 포트

        InMemoryTileBoard
        = 서버 런타임의 authoritative tile state

        EventSeqManager
        = 다음 eventSeq 발급 기준 관리

        ServiceReadiness
        = recovery 전/후 service ready 상태 표시
    */

    private final CheckpointReader checkpointReader;
    private final TileSnapshotLoader tileSnapshotLoader;
    private final WalReplaySource walReplaySource;
    private final InMemoryTileBoard inMemoryTileBoard;
    private final EventSeqManager eventSeqManager;
    private final ServiceReadiness serviceReadiness;

    public StartupRecoveryService(
            CheckpointReader checkpointReader,
            TileSnapshotLoader tileSnapshotLoader,
            WalReplaySource walReplaySource,
            InMemoryTileBoard inMemoryTileBoard,
            EventSeqManager eventSeqManager,
            ServiceReadiness serviceReadiness
    ) {
        this.checkpointReader = checkpointReader;
        this.tileSnapshotLoader = tileSnapshotLoader;
        this.walReplaySource = walReplaySource;
        this.inMemoryTileBoard = inMemoryTileBoard;
        this.eventSeqManager = eventSeqManager;
        this.serviceReadiness = serviceReadiness;
    }

    // recovery 순서는 checkpoint 조회 -> DB tiles 전체 로드 또는 all-white pre-init
    // -> lastFlushedEventSeq 이후 WAL replay -> 마지막 발급 eventSeq 초기화 -> service ready 로 고정됨
    // 이 순서가 바뀌면 메모리 authoritative state와 eventSeq 기준점이 어긋날 수 있음
    public void recover() {
        serviceReadiness.markNotReady();

        /* recovery 시작 기준점 조회 */
        CheckpointSnapshot checkpointSnapshot = checkpointReader.readMainCheckpoint();

        /* DB tiles 전체 로드 또는 all-white pre-init */
        TileLoadResult tileLoadResult = tileSnapshotLoader.loadZ0Tiles();
        if (tileLoadResult.allMissing()) {
            inMemoryTileBoard.initializeAllWhite();
        } else {
            // partial tile 누락은 loadAll 내부에서 실패시켜 조용한 부분 복구를 막음
            inMemoryTileBoard.loadAll(tileLoadResult.snapshots());
        }

        /* DB 반영 완료 마지막 eventSeq 이후 WAL replay */
        WalReplayBatch replayBatch = walReplaySource.readAfter(checkpointSnapshot.lastFlushedEventSeq());
        for (WalRecord record : replayBatch.records()) {
            // WalReplaySource 계약상 records는 이미 lastFlushedEventSeq 초과 이벤트만 담음
            // WAL color는 0~255 int로 검증된 뒤, 메모리 타일의 1 byte 팔레트 저장 표현으로 변환함
            inMemoryTileBoard.applyReplayRecord(record.x(), record.y(), record.color());
        }

        /* 마지막 발급 eventSeq 초기화 */
        // lastFlushedEventSeq는 DB 반영 완료 지점이고, walLastEventSeq는 WAL 파일의 마지막 eventSeq
        eventSeqManager.initializeLastIssued(Math.max(
                checkpointSnapshot.lastFlushedEventSeq(),
                replayBatch.walLastEventSeq()
        ));
        
        /*  service ready 전환
            1. InMemoryTileBoard에 z=0 전체 1024개 타일 존재
            2. DB snapshot이 있으면 그 상태가 메모리에 올라와 있음
            3. lastFlushedEventSeq 이후 WAL 이벤트가 메모리에 replay 완료됨
            4. EventSeqManager가 마지막 발급 완료 eventSeq를 알고 있음
            5. 다음 write는 allocate()로 기존 eventSeq보다 큰 값을 받음
            6. serviceReadiness.ready == true
        */
        serviceReadiness.markReady();
    }
}
