package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.measurement.Measurements;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.recovery.application.*;
import dev.cgt.pixelplace.tile.application.*;
import dev.cgt.pixelplace.tile.domain.*;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** gap/checkpoint/손상별 명시 기대값. 새 graph와 별도 JVM에서 같은 production startup 계약 검증 */
final class WalRecoveryCaseVerifier {
    static void verify(Path wal,long checkpoint,List<Long> expected,String error,WalFileDurability actual) throws Exception {
        var before=hashes(wal);var events=new ArrayList<String>();var keys=new CanonicalZ0TileKeys();
        var board=new InMemoryTileBoard();var seq=new EventSeqManager();var ready=new ServiceReadiness();
        var original=board.getRequired(new TileKey(0,0,0));
        Runnable beforeIo=()-> {
            if(ready.isReady()||seq.currentLastIssued()!=0||board.getRequired(new TileKey(0,0,0))!=original)
                throw new AssertionError("Recovery I/O after memory/seed/READY");
        };
        WalFileDurability traced=new WalFileDurability() {
            public DirectoryProof prepareDirectory(Path path)throws IOException {beforeIo.run();var p=actual.prepareDirectory(path);events.add("P");return p;}
            public void verifyDirectory(DirectoryProof p)throws IOException {actual.verifyDirectory(p);}
            public void syncDirectory(DirectoryProof p)throws IOException {beforeIo.run();actual.syncDirectory(p);events.add("S");}
            public void syncRecoveredFile(Path path)throws IOException {beforeIo.run();actual.syncRecoveredFile(path);events.add("R");}
            public String fileIdentity(Path path)throws IOException {return actual.fileIdentity(path);}
            public NameObservation queryName(Path path,DirectoryProof p){return actual.queryName(path,p);}
        };
        var captured=new AtomicReference<WalReplayBatch>();
        try(var storage=new SegmentedWalStorage(WalStorageTestSupport.properties(wal,1),WalStorageTestSupport.PARSER,
                WalStorageTestSupport.CODEC,Measurements.disabled(),traced)) {
            var snapshots=checkpoint==0?TileLoadResult.allMissingResult():dbView(keys);
            var capture=new StartupRecoveryDbViewCaptureService(()->new CheckpointSnapshot(checkpoint),()->snapshots);
            var recovery=new StartupRecoveryService(capture,new DbBootstrapClassifier(keys),keys,new FileWalReplaySource(storage),
                    board,seq,ready,Measurements.disabled(),batch->{storage.prepareForRecovery(batch);captured.set(batch);beforeIo.run();events.add("prepared");});
            if(!error.equals("none")) {
                Throwable observed=null;try{recovery.recover();}catch(RuntimeException failure){observed=failure;}
                String causes="";for(Throwable f=observed;f!=null;f=f.getCause())causes+=f.getMessage()+" ";
                if(observed==null||!causes.contains(error))throw new AssertionError("Expected recovery rejection missing: "+error);
                beforeIo.run();if(!events.isEmpty()||captured.get()!=null)throw new AssertionError("Invalid input prepared");
            } else {
                recovery.recover();var batch=Objects.requireNonNull(captured.get());
                var observed=batch.records().stream().map(r->r.eventSeq()).toList();
                var replay=expected.stream().filter(v->v>checkpoint).toList();
                long tail=expected.isEmpty()?0:expected.getLast();
                var tile=board.getRequired(new TileKey(0,0,0));
                if(!ready.isReady()||!observed.equals(replay)||batch.walLastEventSeq()!=tail||seq.currentLastIssued()!=tail
                        ||tile.tileVersion()!=replay.size()||Byte.toUnsignedInt(tile.pixels()[0])!=(replay.isEmpty()?15:17))
                    throw new AssertionError("Explicit recovery count/tail/pixels/seed mismatch");
                if(Collections.frequency(events,"R")!=before.size()||!events.getLast().equals("prepared")
                        ||(!before.isEmpty()&&!events.get(events.size()-2).equals("S")))throw new AssertionError("Whole file R/final S order mismatch");
            }
        }
        if(!before.equals(hashes(wal)))throw new AssertionError("Recovery changed original file set/size/hash");
        System.out.println("CASE_VERIFIED checkpoint="+checkpoint+" records="+expected.size()+" rejection="+error+" sequence="+events);
    }
    private static TileLoadResult dbView(CanonicalZ0TileKeys keys) {
        byte[] pixels=new byte[BoardConstants.TILE_PIXEL_COUNT];Arrays.fill(pixels,BoardConstants.DEFAULT_COLOR_INDEX);
        return new TileLoadResult(keys.orderedKeys(),keys.orderedKeys().stream().map(k->new TileStateSnapshot(k,pixels,0)).toList());
    }
    static Map<String,String> hashes(Path wal)throws Exception {
        var values=new TreeMap<String,String>();if(!Files.exists(wal.getParent()))return values;
        try(var paths=Files.list(wal.getParent())) {
            for(var path:paths.filter(p->p.getFileName().toString().equals(wal.getFileName().toString())||p.getFileName().toString().startsWith(wal.getFileName()+".seg-")).toList()) {
                values.put(path.getFileName().toString(),Files.size(path)+":"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return values;
    }
}
