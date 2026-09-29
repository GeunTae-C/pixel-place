package dev.cgt.pixelplace.wal.infra;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.List;
import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** T17-13/14: gap·checkpoint·suffix와 각 손상 상태를 새 graph와 별도 JVM으로 모두 확인 */
class SegmentedWalRecoveryCasesTest {
    @TempDir Path root;
    @ParameterizedTest
    @ValueSource(strings={"initial","empty-legacy","legacy","before-create","empty-tail","checkpoint-split","replay-zero","suffix",
            "no-newline","partial","closed-json","closed-utf8","gap","reverse","checkpoint-above","closed-empty","isolated-empty"})
    void freshGraphAndSeparateJvmPreserveExactCaseAndFileHashes(String point)throws Exception {
        Path wal=root.resolve("wal");long checkpoint=0;String error="none";List<Long> seqs=List.of(2L,5L,9L);
        try(var fixture=storage(wal,1)) {
            switch(point) {
                case "initial" -> seqs=List.of();
                case "empty-legacy" -> {records(wal);seqs=List.of();}
                case "legacy" -> records(wal,2,5,9);
                case "before-create" -> {records(wal,2);seqs=List.of(2L);}
                case "isolated-empty" -> {records(fixture.segmentPath(3));seqs=List.of();error="Empty closed or isolated";}
                default -> {
                    records(wal,2,5);records(fixture.segmentPath(1),9);records(fixture.segmentPath(2));
                    switch(point) {
                        case "checkpoint-split" -> checkpoint=5;
                        case "replay-zero" -> checkpoint=9;
                        case "suffix" -> {Files.move(wal,fixture.segmentPath(4));Files.move(fixture.segmentPath(1),fixture.segmentPath(5));Files.move(fixture.segmentPath(2),fixture.segmentPath(6));checkpoint=5;}
                        case "no-newline" -> {byte[] bytes=CODEC.serializeLine(record(9));Files.write(fixture.segmentPath(1),java.util.Arrays.copyOf(bytes,bytes.length-1));error="not newline-terminated";}
                        case "partial" -> {Files.writeString(fixture.segmentPath(2),"{partial");error="not newline-terminated";}
                        case "closed-json" -> {Files.writeString(wal,"invalid\n");error="Invalid WAL record";}
                        case "closed-utf8" -> {Files.write(wal,new byte[]{(byte)0xc3,0x28,10});error="Invalid UTF-8";}
                        case "gap" -> {Files.move(fixture.segmentPath(2),fixture.segmentPath(3));error="Non-contiguous";}
                        case "reverse" -> {records(fixture.segmentPath(1),2);error="not strictly increasing";}
                        case "checkpoint-above" -> {checkpoint=10;error="tail-before-checkpoint";}
                        case "closed-empty" -> {records(wal);error="Empty closed or isolated";}
                    }
                }
            }
        }
        var hashes=WalRecoveryCaseVerifier.hashes(wal);
        WalRecoveryCaseVerifier.verify(wal,checkpoint,seqs,error,new TestWalFileDurability());
        String expected=seqs.isEmpty()?"empty":String.join(",",seqs.stream().map(Object::toString).toList());
        try(var child=new WalTestJvm(WalRecoveryJvmProbe.class,"--case",wal.toString(),Long.toString(checkpoint),expected,error,"unit")) {
            Files.writeString(child.evidence.resolve("input-hashes.txt"),hashes.toString());
            child.matching("RECOVERY_CASE_EXIT ");child.expectExit(0);
        }
        assertEquals(hashes,WalRecoveryCaseVerifier.hashes(wal));
    }
}
