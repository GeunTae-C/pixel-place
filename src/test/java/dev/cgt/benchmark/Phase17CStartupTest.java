package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 필수 startup 호출의 누락·완료 경합 반례. 합성 trace는 실제 C 앱/native 검증을 대체하지 않음 */
class Phase17CStartupTest {
    static final String PREP="prepareForRecovery", WHITE="initializeAllWhite", LOAD="loadAll",
            REPLAY="applyReplayRecord", SEED="initializeLastIssued", READY="markReady";
    static final class Fixture {
        final Phase17CTrace trace=new Phase17CTrace(1000,1000000);
        final Map<String,Long> open=new HashMap<>();
        final long seed;
        Fixture(long seed){this.seed=seed;}
        void begin(String op){open.put(op,trace.begin(op,op));}
        void end(String op){end(op,null);}
        void end(String op,Throwable error){trace.end(open.remove(op),op,op,op.equals(SEED)?Long.toString(seed):"completed",error);}
        void call(String op){begin(op);end(op);}
        void prepare(){begin(PREP);call("R");call("S");end(PREP);}
    }
    static Phase17CAnalyzer.StartupExpectation expected(String action,long checkpoint) {
        return Phase17CVerificationMain.startupExpectation(action,BenchmarkJson.MAPPER.valueToTree(Map.of("checkpoint",checkpoint)));
    }
    @ParameterizedTest @ValueSource(strings={"smoke","recover-drained","recover-unflushed"})
    void v1101AcceptsActionSpecificMemoryReplayCountAndObservedStartupSeed(String action) {
        var expected=expected(action,42);var f=new Fixture(expected.seed());f.prepare();f.call(expected.memoryOperation());
        // 미반영 fixture의 실제 두 eventSeq 2/5. seq 차이를 replay 건수로 사용하지 않는 경계
        var fixtureSequences=Phase17CFixtures.records(List.of(101L,102L)).stream().map(dev.cgt.pixelplace.wal.domain.WalRecord::eventSeq).toList();
        assertEquals(List.of(2L,5L),fixtureSequences);
        var sequences=action.equals("recover-unflushed")?fixtureSequences:List.<Long>of();
        for(long ignored:sequences)f.call(REPLAY);
        f.call(SEED);f.call(READY);
        assertDoesNotThrow(()->Phase17CAnalyzer.trace(f.trace,expected));
    }
    @Test void v1102RejectsReadyThenMemoryThenSeedAfterPreparation() {
        var f=new Fixture(0);f.prepare();f.call(READY);f.call(WHITE);f.call(SEED);
        reject(f,expected("smoke",0),"Startup completion order");
    }
    static Stream<String> overlaps(){return Stream.of("preparation-memory","memory-seed","seed-ready","load-replay","white-replay","replay-seed","replay-replay");}
    @ParameterizedTest @MethodSource("overlaps")
    void v1102RejectsCorrectEntryOrderWithOverlappingCompletion(String overlap) {
        var f=new Fixture(5);
        String memory=overlap.equals("white-replay")?WHITE:LOAD;
        int replays=overlap.equals("load-replay")||overlap.equals("white-replay")||overlap.equals("replay-seed")?1:overlap.equals("replay-replay")?2:0;
        if(overlap.equals("preparation-memory")){f.begin(PREP);f.call("R");f.call("S");f.call(memory);f.end(PREP);}
        else{f.prepare();if(overlap.equals("memory-seed")){f.begin(memory);f.call(SEED);f.end(memory);}
            else if(overlap.equals("load-replay")||overlap.equals("white-replay")){f.begin(memory);f.call(REPLAY);f.end(memory);}
            else f.call(memory);}
        if(overlap.equals("replay-seed")){f.begin(REPLAY);f.call(SEED);f.end(REPLAY);}
        if(overlap.equals("replay-replay")){
            long first=f.trace.begin(REPLAY,REPLAY);f.call(REPLAY);f.trace.end(first,REPLAY,REPLAY,"completed",null);
        }
        if(overlap.equals("seed-ready")){f.begin(SEED);f.call(READY);f.end(SEED);}
        else{if(!Set.of("memory-seed","replay-seed").contains(overlap))f.call(SEED);f.call(READY);}
        assertTrue(f.trace.complete());
        assertDoesNotThrow(()->Phase17CAnalyzer.integrity(f.trace));
        reject(f,new Phase17CAnalyzer.StartupExpectation(memory,replays,5,true),"Startup completion order");
    }
    static Stream<org.junit.jupiter.params.provider.Arguments> requiredDefects(){
        return Stream.of(PREP,WHITE,LOAD,SEED,READY).flatMap(op->Stream.of("missing","unreturned","duplicate","failed")
                .map(defect->org.junit.jupiter.params.provider.Arguments.of(op,defect)));
    }
    @ParameterizedTest @MethodSource("requiredDefects")
    void v1103RejectsEachRequiredCallMissingUnreturnedDuplicateOrFailed(String op,String defect) {
        var f=new Fixture(0);
        for(String current:List.of(PREP,op.equals(LOAD)?LOAD:WHITE,SEED,READY)){
            if(!current.equals(op)){if(current.equals(PREP))f.prepare();else f.call(current);continue;}
            switch(defect){
                case "missing" -> { }
                case "unreturned" -> f.begin(current);
                case "duplicate" -> {f.call(current);f.call(current);}
                case "failed" -> {f.begin(current);f.end(current,new IllegalStateException("fixture"));}
            }
        }
        assertTrue(f.trace.complete());
        reject(f,expected(op.equals(LOAD)?"recover-drained":"smoke",0),switch(defect){case "unreturned"->"Missing close/return";case "failed"->"Actual operation failed";default->"Startup call count mismatch";});
    }
    @Test void v1103RejectsBothInitializationMethods() {
        var f=new Fixture(0);f.prepare();f.call(WHITE);f.call(LOAD);f.call(SEED);f.call(READY);
        reject(f,expected("smoke",0),"Startup call count mismatch");
    }
    @ParameterizedTest @ValueSource(ints={0,1,3})
    void v1103RejectsReplayCountOtherThanTwoForFixedUnflushedFixture(int count) {
        var f=new Fixture(5);f.prepare();f.call(LOAD);for(int i=0;i<count;i++)f.call(REPLAY);f.call(SEED);f.call(READY);
        reject(f,expected("recover-unflushed",0),"Startup call count mismatch");
    }
    @ParameterizedTest @ValueSource(strings={"smoke","recover-drained","recover-unflushed"})
    void v1104RejectsWrongMemoryOperationForEachAction(String action) {
        var e=expected(action,42);var f=new Fixture(e.seed());f.prepare();f.call(e.memoryOperation().equals(WHITE)?LOAD:WHITE);
        for(int i=0;i<e.replayCount();i++)f.call(REPLAY);f.call(SEED);f.call(READY);
        reject(f,e,"Startup call count mismatch");
    }
    @ParameterizedTest @ValueSource(strings={"smoke","recover-drained","recover-unflushed"})
    void v1104RejectsWrongObservedSeedForEachAction(String action) {
        var e=expected(action,42);var f=new Fixture(e.seed()+1);f.prepare();f.call(e.memoryOperation());
        for(int i=0;i<e.replayCount();i++)f.call(REPLAY);f.call(SEED);f.call(READY);
        reject(f,e,"Startup seed argument mismatch");
    }
    @ParameterizedTest @ValueSource(strings={WHITE,LOAD,REPLAY,SEED})
    void v1104RejectsExtraStartupMemoryOrSeedAfterReady(String op) {
        var f=new Fixture(0);f.prepare();f.call(WHITE);f.call(SEED);f.call(READY);f.call(op);
        reject(f,expected("smoke",0),"Startup call count mismatch");
    }
    @Test void v1103RejectsWrongCallOperationOrTargetPairing() {
        for(String wrong:List.of("operation","target")){
            var f=new Fixture(0);long id=f.trace.begin(PREP,PREP);
            f.trace.end(id,wrong.equals("operation")?"wrong":PREP,wrong.equals("target")?"wrong":PREP,"completed",null);
            f.call(WHITE);f.call(SEED);f.call(READY);
            reject(f,expected("smoke",0),"Unpaired trace");
        }
    }
    @Test void v1101DrainedUsesInitialCheckpointAndRejectsAbsentOrInvalidInput() {
        assertEquals(987,expected("recover-drained",987).seed());
        assertThrows(IllegalStateException.class,()->Phase17CVerificationMain.startupExpectation("recover-drained",null));
        for(var input:List.of(Map.of(),Map.of("checkpoint","42")))
            assertThrows(IllegalStateException.class,()->Phase17CVerificationMain.startupExpectation("recover-drained",BenchmarkJson.MAPPER.valueToTree(input)));
        assertThrows(IllegalArgumentException.class,()->expected("recover-drained",-1));
    }
    @Test void v1101ObservedSeedRemainsStartupArgumentAfterRuntimeSequenceAdvances() {
        var f=new Fixture(0);f.prepare();f.call(WHITE);
        var actual=new dev.cgt.pixelplace.pixel.application.EventSeqManager();
        var observation=new Phase17CObservation(f.trace,java.nio.file.Path.of("unused-wal"));
        var observed=(dev.cgt.pixelplace.pixel.application.EventSeqManager)observation.postProcessAfterInitialization(actual,"eventSeqManager");
        observed.initializeLastIssued(0);f.call(READY);
        // 단위 fixture의 업무 호출만 seq 전진. 관측기는 발급·drain·추가 scan을 수행하지 않음
        for(int i=0;i<65;i++)actual.allocate();
        assertEquals(65,observed.currentLastIssued());
        assertDoesNotThrow(()->Phase17CAnalyzer.trace(f.trace,expected("smoke",0)));
    }
    private static void reject(Fixture f,Phase17CAnalyzer.StartupExpectation expected,String message){
        var failure=assertThrows(IllegalStateException.class,()->Phase17CAnalyzer.trace(f.trace,expected));
        assertTrue(failure.getMessage().contains(message),failure.getMessage());
    }
}
