package dev.cgt.benchmark;

import java.nio.file.*;
import java.util.*;

/** plan 원본 해시에 묶인 A-3 안전 입력. 사후 한도 변경과 임의 fallback 해석 차단 */
public record Phase18ObservationPolicy(int schemaVersion, String planHash, String caseId,
        long maxHeapBytes, long maxBacklogRecords, int maxOutstanding, int maxWalFiles,
        int maxTraceEntries, long maxTraceBytes, long maxSampleBytes, String fixture) {
    static Phase18ObservationPolicy read(Path file, Phase18Plan.Loaded loaded) throws Exception {
        Phase18Plan.require(Files.size(file) <= 8192, "observation policy bytes");
        Phase18ObservationPolicy value;
        try(var in=Files.newInputStream(file)){
            byte[] bytes=in.readNBytes(8193);Phase18Plan.require(bytes.length<=8192,"observation policy grew");
            try{value=Phase18Plan.JSON.readValue(bytes,Phase18ObservationPolicy.class);}
            catch(RuntimeException malformed){throw new IllegalArgumentException("Invalid observation policy shape");}
        }
        value.validate(loaded); return value;
    }
    void validate(Phase18Plan.Loaded loaded) {
        var p = loaded.plan(); var c = p.cases().getFirst(); var o = c.observation();
        Phase18Plan.require(schemaVersion == 1 && planHash.equals(loaded.planHash()) && caseId.equals(c.caseId()), "observation identity");
        Phase18Plan.require(maxHeapBytes > 0 && maxHeapBytes <= 2L * 1024 * 1024 * 1024
                && maxBacklogRecords > 0 && maxBacklogRecords <= Phase18Plan.TERMINALS
                && maxOutstanding > 0 && maxOutstanding <= 128 && maxWalFiles > 0 && maxWalFiles <= 4096
                && maxTraceEntries > 0 && maxTraceEntries <= 100_000 && maxTraceBytes > 0 && maxTraceBytes <= 16_777_216
                && maxSampleBytes >= 4096 && maxSampleBytes <= p.bounds().outputBytes(), "observation allocation limits");
        Phase18Plan.require(new HashSet<>(o.required()).equals(Set.of("heap", "outstanding", "backlog", "disk", "deadline", "metrics", "wal-metadata"))
                && Set.of("after-gc", "process-io", "host-disk").containsAll(o.auxiliary())
                && o.fallback().equals("planned-writes-upper-bound") && o.stopRule().equals("stop-new-dispatch"), "implemented observation policy");
        Phase18Plan.require(Set.of("none", "collector-failure", "safety-heap").contains(fixture), "observation fixture");
        Phase18Plan.require(Phase18Execution.fixture(p) || fixture.equals("none"), "no injected failure in official trial");
        // initial/recovery 각각의 server sample+trace, 발생기 표본, 기존 terminal/receipt 예약까지 포함
        long reservation=Math.addExact(Math.multiplyExact(2,Math.addExact(maxSampleBytes,maxTraceBytes)),
                Math.addExact(p.bounds().outputBytes()/4,p.counts(c).diskBytes()));
        Phase18Plan.require(reservation<=p.bounds().outputBytes(),"combined observation output reservation");
    }
}
