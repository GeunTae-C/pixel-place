package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.measurement.Measurements;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.recovery.application.*;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.domain.*;
import java.nio.file.*;
import java.util.*;

/** 실패 fixture의 실제 남은 파일을 새 JVM production recovery로 검증. DB/schema/seed/users 변경 기능 없음 */
public final class WalRecoveryJvmProbe {
    public static void main(String[] args) throws Exception {
        if(args[0].equals("--case")) {
            Path wal=Path.of(args[1]);long checkpoint=Long.parseLong(args[2]);
            List<Long> records=args[3].equals("empty")?List.of():Arrays.stream(args[3].split(",")).map(Long::valueOf).toList();
            if(args[5].equals("native"))try(var boundary=new WindowsWalNativeTestBoundary()) {
                WalRecoveryCaseVerifier.verify(wal,checkpoint,records,args[4],new WindowsWalFileDurability(boundary.bridge));
            } else WalRecoveryCaseVerifier.verify(wal,checkpoint,records,args[4],new TestWalFileDurability());
            System.out.println("RECOVERY_CASE_EXIT pid="+ProcessHandle.current().pid());return;
        }
        Path wal = Path.of(args[0]); int expected = Integer.parseInt(args[1]);
        var before = hashes(wal);
        var measure = Measurements.disabled(); var ready = new ServiceReadiness();
        var board = new InMemoryTileBoard(); var seq = new EventSeqManager(); var keys = new CanonicalZ0TileKeys();
        var originalTile = board.getRequired(new TileKey(0,0,0));
        try (var storage = WalStorageTestSupport.storage(wal, 1)) {
            var capture = new StartupRecoveryDbViewCaptureService(() -> new CheckpointSnapshot(0), TileLoadResult::allMissingResult);
            var recovery = new StartupRecoveryService(capture, new DbBootstrapClassifier(keys), keys,
                    new FileWalReplaySource(storage), board, seq, ready, measure, storage::prepareForRecovery);
            if (expected < 0) {
                boolean failed = false;
                try { recovery.recover(); } catch (IllegalStateException failure) { failed = true; }
                if (!failed || ready.isReady() || board.size() != 1024 || seq.currentLastIssued() != 0
                        || board.getRequired(new TileKey(0,0,0)) != originalTile || originalTile.tileVersion() != 0)
                    throw new AssertionError("Partial line must fail before memory/seed/ready");
            } else {
                recovery.recover();
                var batch = storage.readAfter(0);
                var tile = board.getRequired(new TileKey(0,0,0));
                if (!ready.isReady() || batch.records().size() != expected || batch.walLastEventSeq() != expected
                        || seq.currentLastIssued() != expected || tile.tileVersion() != expected
                        || Byte.toUnsignedInt(tile.pixels()[0]) != expected)
                    throw new AssertionError("Surviving records/memory/seed mismatch");
            }
        }
        if (!before.equals(hashes(wal))) throw new AssertionError("Recovery rewrote original files");
        System.out.println("RECOVERY_FILE_STATE_VERIFIED expected=" + expected + " pid=" + ProcessHandle.current().pid());
    }
    private static Map<String,String> hashes(Path wal) throws Exception {
        var values = new TreeMap<String,String>();
        try (var files = Files.list(wal.getParent())) {
            for (var path : files.filter(p -> p.getFileName().toString().equals(wal.getFileName().toString())
                    || p.getFileName().toString().startsWith(wal.getFileName()+".seg-")).toList())
                values.put(path.getFileName().toString(), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        return values;
    }
}
