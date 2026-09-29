package dev.cgt.benchmark;

import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

/** C-V03/09의 경로 오염·증거 누락·seq gap 경계. 합성 결과를 실제 native 성공으로 사용하지 않음 */
class Phase17CContractTest {
    @TempDir Path temporary;
    private static WalRecord record(long seq) { return new WalRecord(seq, seq, 0, 0, 0, 0, 0, 1, LocalDateTime.of(2026,9,28,0,0)); }
    private static List<WalRecord> records(long... seq) { return LongStream.of(seq).mapToObj(Phase17CContractTest::record).toList(); }
    @Test void retainedComparisonExcludesDeletedPrefixWithoutRequiringEqualTotalCount() {
        assertDoesNotThrow(() -> Phase17CAnalyzer.retained(records(2,5,8,11),records(8,11),11,11));
    }
    @Test void retainedComparisonAllowsActualSequenceGaps() {
        assertDoesNotThrow(() -> Phase17CAnalyzer.retained(records(2,5,8),records(2,5,8),8,8));
    }
    @Test void matchingTailAndCoverageCannotHideMissingMiddleRecord() {
        var database=LongStream.rangeClosed(1,65).mapToObj(Phase17CContractTest::record).toList();
        assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.retained(database,records(61,62,64,65),65,65));
    }
    @Test void retainedComparisonRejectsEmptyDuplicateReverseExtraAndPayloadMismatch() {
        for(var wal:List.of(List.<WalRecord>of(),records(2,5,5,8),records(5,2,8),records(2,4,5,8),records(2,8)))
            assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.retained(records(2,5,8),wal,8,8));
        var wrong=new WalRecord(5,99,0,0,0,0,0,1,LocalDateTime.now());
        assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.retained(records(2,5,8),List.of(record(2),wrong,record(8)),8,8));
        assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.retained(records(2,5,8,11),records(2,5,8),8,8));
    }
    @ParameterizedTest @ValueSource(strings={"C:temp","\\temp","/temp","relative","\\\\host\\share","\\\\?\\C:\\temp",""})
    void incompleteAndDevicePathsRejectBeforeAccess(String path) { assertThrows(IllegalArgumentException.class,()->Phase17CPlan.absolute(path)); }
    @Test void exactPathsAllowNormalizationButRejectDifferentPlanAndSiblingPrefix() throws Exception {
        assertEquals(Path.of("C:/one/two"),Phase17CPlan.exact("C:/one/./two/","C:\\one\\two"));
        assertThrows(IllegalArgumentException.class,()->Phase17CPlan.exact("C:/one","C:/two"));
        assertThrows(IllegalArgumentException.class,()->Phase17CPlan.owned(temporary.resolveSibling(temporary.getFileName()+"-other"),temporary));
        assertDoesNotThrow(()->Phase17CPlan.owned(temporary.resolve("mysql/data"),temporary));
    }
    @Test void wrongCaseActionUnknownOrDuplicateArgumentsAndClasspathReject() {
        assertThrows(IllegalArgumentException.class,()->Phase17CPlan.arguments(new String[]{"--plan","C:/plan.json","--case","single-smoke","--action","recover-unflushed"}));
        assertThrows(IllegalArgumentException.class,()->Phase17CPlan.arguments(new String[]{"--plan","C:/plan.json","--case","single-smoke","--case","single-smoke"}));
        assertThrows(IllegalArgumentException.class,()->Phase17CPlan.arguments(new String[]{"--plan","C:/plan.json","--case","single-smoke","--unknown","smoke"}));
        assertDoesNotThrow(()->Phase17CPlan.arguments(new String[]{"--plan","C:/plan.json","--case","group-unflushed","--action","prepare-unflushed"}));
        for(String jar:List.of("junit.jar","mockito.jar","spring-boot-devtools.jar","classes/java/test"))assertThrows(IllegalArgumentException.class,()->Phase17CPlan.classpath("C:/lib/"+jar));
        assertDoesNotThrow(()->Phase17CPlan.classpath("C:/classes/java/benchmark;C:/lib/jna.jar"));
    }
    @Test void redisProductionRegressionAndRuntimeDatabasesRemainExcluded() {
        for(int database:new int[]{0,1,2})assertThrows(IllegalArgumentException.class,()->BenchmarkGuards.redis(database,2,"127.0.0.1"));
        assertDoesNotThrow(()->BenchmarkGuards.redis(3,2,"127.0.0.1"));
    }
    private static void call(Phase17CTrace trace,String operation,String target,String result,Throwable error) { long id=trace.begin(operation,target);trace.end(id,operation,target,result,error); }
    private static void sync(Phase17CTrace trace,String directory) {
        long outer=trace.begin("S",directory), inner=trace.begin("native.S",directory);
        call(trace,"native.open",directory,"7",null);call(trace,"native.flush","7","completed",null);call(trace,"native.close","7","completed",null);
        trace.end(inner,"native.S",directory,"completed",null);trace.end(outer,"S",directory,"completed",null);
    }
    private static Phase17CTrace trace(boolean memoryFirst,boolean close,boolean closeFailure) {
        var trace=new Phase17CTrace(1000,1000000);
        if(memoryFirst)call(trace,"loadAll","board","completed",null);
        long prepare=trace.begin("prepareForRecovery","preparation");
        long r=trace.begin("R","wal");call(trace,"native.open","wal","123",null);call(trace,"native.flush","123","completed",null);
        if(close)call(trace,"native.close","123","completed",closeFailure?new IllegalStateException("fixture"):null);
        trace.end(r,"R","wal","completed",null);call(trace,"S","parent","completed",null);trace.end(prepare,"prepareForRecovery","preparation","completed",null);
        if(!memoryFirst)call(trace,"loadAll","board","completed",null);
        call(trace,"initializeLastIssued","seed","5",null);call(trace,"markReady","readiness","completed",null);return trace;
    }
    @Test void traceRejectsMissingOrFailedCloseAndReversedPreparationButAllowsCompleteFixture() {
        var expected = new Phase17CAnalyzer.StartupExpectation("loadAll",0,5,true);
        assertDoesNotThrow(()->Phase17CAnalyzer.trace(trace(false,true,false),expected));
        assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.trace(trace(false,false,false),expected));
        assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.trace(trace(false,true,true),expected));
        assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.trace(trace(true,true,false),expected));
    }
    @Test void overflowNeverThrowsIntoProductionButPermanentlyInvalidatesEvidence() {
        var trace=new Phase17CTrace(1,64);
        assertDoesNotThrow(()->call(trace,"prepareForRecovery","owned","completed",null));
        assertFalse(trace.complete());assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.trace(trace,new Phase17CAnalyzer.StartupExpectation("initializeAllWhite",0,0,false)));
    }
    @Test void coverageUsesActualRecordCountsAndIncludesInitialEmptyForce() {
        var counts=new PixelMeasurement.WalCounts(65,0,0,65,65,65,2,0,1,Map.of());
        assertDoesNotThrow(()->Phase17CAnalyzer.coverage(counts,65,"single"));
        assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.coverage(counts,64,"single"));
    }
    @Test void preparationRequiresEveryCapturedFileAndLastNativeSyncWithClose() {
        Path wal=temporary.resolve("pixel-place.wal");var t=new Phase17CTrace(1000,1000000);
        long p=t.begin("prepareForRecovery","prep");
        long r=t.begin("R",wal.toString());call(t,"native.open",wal.toString(),"1",null);call(t,"native.flush","1","completed",null);call(t,"native.close","1","completed",null);t.end(r,"R",wal.toString(),"completed",null);
        long s=t.begin("native.S",wal.getParent().toString());call(t,"native.open",wal.getParent().toString(),"2",null);call(t,"native.flush","2","completed",null);call(t,"native.close","2","completed",null);t.end(s,"native.S",wal.getParent().toString(),"completed",null);
        t.end(p,"prepareForRecovery","prep","completed",null);
        assertDoesNotThrow(()->Phase17CAnalyzer.preparation(t,Map.of("pixel-place.wal","hash"),wal));
        assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.preparation(t,Map.of("pixel-place.wal","hash","pixel-place.wal.seg-0000000000000000001","hash"),wal));
    }
    @Test void nativeHandleReuseCannotHideAnEarlierMissingClose() {
        var t=new Phase17CTrace(100,100000);call(t,"native.open","first","1",null);call(t,"native.open","second","1",null);call(t,"native.close","1","completed",null);
        assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.integrity(t));
    }
    @Test void retentionRequiresExactCountAndBothQueriesAroundSameParentSync() {
        var t=new Phase17CTrace(100,100000);String file=temporary.resolve("pixel-place.wal").toString();
        long id=t.begin("deleteCommittedPrefix","storage");call(t,"file.delete",file,"completed",null);call(t,"Q",file,"ABSENT:parentSafe=true",null);sync(t,temporary.toString());call(t,"Q",file,"ABSENT:parentSafe=true",null);t.end(id,"deleteCommittedPrefix","storage","deleted=1;delayed=false",null);
        assertDoesNotThrow(()->Phase17CAnalyzer.physical(t));
        var wrong=new Phase17CTrace(100,100000);long call=wrong.begin("deleteCommittedPrefix","storage");call(wrong,"file.delete",file,"completed",null);wrong.end(call,"deleteCommittedPrefix","storage","deleted=1;delayed=false",null);
        assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.physical(wrong));
    }
    @Test void runtimeSyncCannotPassWithOnlyHighLevelOrMissingNativeFlush() {
        var onlyHigh=new Phase17CTrace(100,100000);call(onlyHigh,"S",temporary.toString(),"completed",null);
        assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.physical(onlyHigh));
        var missingFlush=new Phase17CTrace(100,100000);long s=missingFlush.begin("native.S",temporary.toString());
        call(missingFlush,"native.open",temporary.toString(),"7",null);call(missingFlush,"native.close","7","completed",null);missingFlush.end(s,"native.S",temporary.toString(),"completed",null);
        assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.physical(missingFlush));
    }
    @Test void readerCloseCannotSubstituteForWriterCloseOrHideOverlappingWriters() {
        String file=temporary.resolve("pixel-place.wal").toString();
        for(int variant=0;variant<3;variant++) {
            var t=new Phase17CTrace(1000,1000000);
            call(t,"file.create",file,"completed",null);call(t,"empty_force",file,"completed",null);sync(t,temporary.toString());
            call(t,"file.read",file,"completed",null);call(t,"file.read.close",file,"completed",null);
            call(t,"record_force",file,"completed",null);
            if(variant==2) {
                String next=file+".seg-0000000000000000001";
                call(t,"file.create",next,"completed",null);call(t,"empty_force",next,"completed",null);sync(t,temporary.toString());
                call(t,"file.close",file,"completed",null);call(t,"file.close",next,"completed",null);
            } else if(variant==0) call(t,"file.close",file,"completed",null);
            // reader 수명은 writer 회전과 별개. writer close 누락/중첩 거부 계약 유지
            if(variant==0) assertDoesNotThrow(()->Phase17CAnalyzer.physical(t));
            else assertThrows(RuntimeException.class,()->Phase17CAnalyzer.physical(t));
        }
    }
}
