package dev.cgt.benchmark;

import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.pixel.application.ExecutionSnapshot;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** C 입력·예산·필수 drain과 force coverage를 off에서도 동일하게 적용하는 도구 계약 */
class BenchmarkCContractTest {
    @org.junit.jupiter.api.io.TempDir Path temporary;
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void realObserverKeepsSafetyAndMarksEveryOptionalDisabledField(boolean enabled) throws Exception {
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var measurement=new PixelMeasurement(enabled,registry);
        measurement.commandEntered();measurement.captured(7,7,0);measurement.broadcastFailure();
        var sampled=new java.util.concurrent.CountDownLatch(1);
        var executor=org.mockito.Mockito.mock(dev.cgt.pixelplace.pixel.application.PixelWriteExecutor.class);
        org.mockito.Mockito.when(executor.snapshot()).thenAnswer(call->{sampled.countDown();return new ExecutionSnapshot(0,0,0,0,0,ExecutionSnapshot.State.RUNNING,0,true);});
        var spec=BenchmarkSpec.read(Path.of("scripts/phase15-c4-input.json"));
        var observer=new BenchmarkObserver(measurement,registry,new dev.cgt.pixelplace.flush.application.PendingAmbiguousFlushStore(),
                new BenchmarkServletConfiguration.Activity(),executor,temporary.resolve("wal"),spec,spec.plan(spec.cases().getFirst()));
        try { assertTrue(sampled.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
        finally { observer.close(); }
        assertTrue(observer.complete());observer.write(temporary);
        var row=BenchmarkJson.read(temporary.resolve("resource-samples.json")).path("server").get(0);
        assertEquals(1,row.path("commandActive").asInt());assertEquals(7,row.path("capture").path("tail").asInt());
        assertTrue(row.path("executor").path("workerAlive").asBoolean());
        var metrics=BenchmarkJson.read(temporary.resolve("server-metrics.json"));
        if (enabled) {assertEquals(1,row.path("broadcastFailures").asInt());assertTrue(metrics.path("disabledFields").isEmpty());}
        else {
            assertTrue(row.path("broadcastFailures").isNull());assertTrue(row.path("lastCycle").isNull());
            assertTrue(metrics.path("walCounts").path("batchSizes").isNull());assertTrue(metrics.path("timers").isEmpty());
            for(String field:List.of("timers","batchSizes","lastCycle","broadcastFailures"))
                assertEquals("instrumentation off",metrics.path("disabledFields").path(field).asText());
        }
    }
    @Test void approvedCarryFloorAndExactCapFreeSpaceAndOverflowAreEnforced() throws Exception {
        var a = BenchmarkStorageBudget.approval(Path.of("scripts/phase15-c-budget.json"));
        assertEquals(36_960_813_040L, BenchmarkStorageBudget.charged(0, a));
        assertTrue(BenchmarkStorageBudget.permits(a.maximumBytes(), 0, a.minimumFreeBytes(), a));
        assertFalse(BenchmarkStorageBudget.permits(a.maximumBytes(), 1, a.minimumFreeBytes()+1, a));
        assertFalse(BenchmarkStorageBudget.permits(a.carriedChargeBytes(), 1, a.minimumFreeBytes(), a));
        assertFalse(BenchmarkStorageBudget.permits(a.carriedChargeBytes(), Long.MAX_VALUE, Long.MAX_VALUE, a));
        assertThrows(ArithmeticException.class, () -> BenchmarkStorageBudget.charged(Long.MAX_VALUE, a));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkStorageBudget.validate(
                new BenchmarkStorageBudget.Approval(a.maximumBytes()+1, a.carriedChargeBytes(), a.legacyChargeBytes(), a.ancillaryChargeBytes(), a.minimumFreeBytes())));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkStorageBudget.validate(
                new BenchmarkStorageBudget.Approval(a.maximumBytes(), a.carriedChargeBytes()-1, a.legacyChargeBytes(), a.ancillaryChargeBytes(), a.minimumFreeBytes())));
    }
    @Test void cumulativeChargeNeverRefundsTemporaryFilesAndReadsOwnedLedger() throws Exception {
        var a = BenchmarkStorageBudget.approval(Path.of("scripts/phase15-c-budget.json"));
        long first = BenchmarkStorageBudget.continuedCharge(120, 100, a.carriedChargeBytes(), a);
        assertEquals(a.carriedChargeBytes()+20, first);
        long afterRelease = BenchmarkStorageBudget.continuedCharge(80, 120, first, a);
        assertEquals(first, afterRelease);
        assertEquals(first+10, BenchmarkStorageBudget.continuedCharge(90, 80, afterRelease, a));
        assertThrows(ArithmeticException.class, () -> BenchmarkStorageBudget.continuedCharge(Long.MAX_VALUE, 0, first, a));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkStorageBudget.continuedCharge(-1, 0, first, a));
        Path ledger = temporary.resolve("agent-runs/phase15-C-4-budget-ledger");
        java.nio.file.Files.createDirectories(ledger);
        BenchmarkJson.write(ledger.resolve("00000001.json"), Map.of("maximumBytes",a.maximumBytes(),"ownedBytes",100,"chargedBytes",first));
        BenchmarkJson.write(ledger.resolve("00000002.json"), Map.of("maximumBytes",a.maximumBytes(),"ownedBytes",80,"chargedBytes",first));
        assertEquals(first+10, BenchmarkStorageBudget.currentCharge(temporary,90,a));
        BenchmarkJson.write(ledger.resolve("00000003.json"), Map.of("maximumBytes",a.maximumBytes()+1,"ownedBytes",80,"chargedBytes",first));
        assertThrows(IllegalStateException.class, () -> BenchmarkStorageBudget.currentCharge(temporary,90,a));
    }
    @Test void matrixContainsExactlyFifteenInFixedOrderAndNeverRunsComparisonInC3() throws Exception {
        var matrix = BenchmarkJson.read(Path.of("scripts/phase15-c-matrix.json")); BenchmarkMatrix.validate(matrix);
        assertEquals(15, BenchmarkMatrix.entries().size());
        assertEquals(12, BenchmarkMatrix.entries().stream().filter(e -> e.role().equals("target")).count());
        var spec = BenchmarkSpec.read(Path.of("scripts/phase15-c4-input.json"));
        for (var row : BenchmarkMatrix.entries()) {
            BenchmarkMatrix.validateTrial(matrix, row.id(), spec, row.caseIndex(), row.repetition(), row.mode(), row.instrumentation(), "C-4");
            assertThrows(IllegalArgumentException.class, () -> BenchmarkMatrix.validateTrial(matrix, row.id(), spec, row.caseIndex(), row.repetition(), row.mode(), row.instrumentation(), "C-3"));
        }
        var changed = (tools.jackson.databind.node.ObjectNode) matrix.deepCopy();
        changed.putArray("entries"); assertThrows(IllegalArgumentException.class, () -> BenchmarkMatrix.validate(changed));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkMatrix.validateTrial(matrix, "r1-same-single", spec, 0, 0, "group", true, "C-4"));
        var changedSpec = (tools.jackson.databind.node.ObjectNode) BenchmarkJson.MAPPER.valueToTree(spec);
        changedSpec.put("targetP95Millis",200);
        var weaker = BenchmarkJson.MAPPER.treeToValue(changedSpec,BenchmarkSpec.class);
        assertThrows(IllegalArgumentException.class, () -> BenchmarkMatrix.validateTrial(matrix,"r1-same-single",weaker,0,0,"single",true,"C-4"));
    }
    @Test void drainRequiresEveryExecutionRemainderAndRunningEvenWhenWorkerIsAliveOrFailedClosed() {
        for (boolean alive : List.of(true, false)) {
            assertTrue(Phase15BenchmarkMain.executorIdle(new ExecutionSnapshot(0,0,0,0,0,ExecutionSnapshot.State.RUNNING,0,alive)));
            for (int i=0;i<6;i++) {
                int[] c = new int[6]; c[i]=1;
                assertFalse(Phase15BenchmarkMain.executorIdle(new ExecutionSnapshot(c[0],c[1],c[2],c[3],c[4],ExecutionSnapshot.State.RUNNING,c[5],alive)));
            }
        }
        for (var state : List.of(ExecutionSnapshot.State.STOPPING, ExecutionSnapshot.State.STOPPED, ExecutionSnapshot.State.FAILED))
            assertFalse(Phase15BenchmarkMain.executorIdle(new ExecutionSnapshot(0,0,0,0,0,state,0,false)));
    }
    @Test void lowerForceCountAloneCannotPassAndDisabledDistributionIsNotZeroFilled() {
        var valid = new PixelMeasurement.WalCounts(0,2,4,4,3,4,1,0,1,Map.of(1,1L,3,1L));
        Phase15BenchmarkMain.verifyCoverage(valid,4,"group");
        Phase15BenchmarkMain.verifyCoverage(new PixelMeasurement.WalCounts(0,2,4,4,3,4,1,0,1,null),4,"group");
        assertThrows(IllegalStateException.class, () -> Phase15BenchmarkMain.verifyCoverage(new PixelMeasurement.WalCounts(0,2,4,4,1,3,1,0,1,null),4,"group"));
        assertThrows(IllegalStateException.class, () -> Phase15BenchmarkMain.verifyCoverage(valid,4,"single"));
        assertThrows(IllegalStateException.class, () -> Phase15BenchmarkMain.verifyCoverage(new PixelMeasurement.WalCounts(0,2,4,4,3,4,1,1,1,null),4,"group"));
    }
}
