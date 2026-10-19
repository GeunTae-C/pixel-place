package dev.cgt.benchmark;

import java.nio.file.*;
import java.util.*;

/** plan 원본 해시에 묶인 A-3 안전 입력. 사후 한도 변경과 임의 fallback 해석 차단 */
public record Phase18ObservationPolicy(int schemaVersion, String planHash, String caseId,
        long maxHeapBytes, long maxBacklogRecords, int maxOutstanding, int maxWalFiles,
        int maxTraceEntries, long maxTraceBytes, long maxSampleBytes, String fixture) {
    static Phase18ObservationPolicy read(Path file, Phase18Plan.Loaded loaded) throws Exception {
        Phase18Paths.execution(file.toString());
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
                && maxTraceEntries > 0 && maxTraceEntries <= (Phase18Cap1.enabled(p)?Phase18Cap1.limit(6000,Phase18Cap1.output(p).traceEntries()):100_000) && maxTraceBytes > 0
                && maxTraceBytes <= (Phase18Cap1.enabled(p)?Phase18Cap1.limit(2097152,Phase18Cap1.output(p).traceBytes()):Phase18K6Session.expandedS2(p)?52_428_800:16_777_216)
                && maxSampleBytes >= 4096 && maxSampleBytes <= p.bounds().outputBytes(), "observation allocation limits");
        Phase18Plan.require(new HashSet<>(o.required()).equals(Set.of("heap", "outstanding", "backlog", "disk", "deadline", "metrics", "wal-metadata"))
                && Set.of("after-gc", "process-io", "host-disk").containsAll(o.auxiliary())
                && o.fallback().equals("planned-writes-upper-bound") && o.stopRule().equals("stop-new-dispatch"), "implemented observation policy");
        Phase18Plan.require(Set.of("none", "collector-failure", "safety-heap").contains(fixture), "observation fixture");
        Phase18Plan.require(Phase18Execution.fixture(p) || fixture.equals("none"), "no injected failure in official trial");
        // initial/recovery 각각의 server sample+trace, 발생기 표본, 기존 terminal/receipt 예약까지 포함
        boolean k6=System.getProperty("phase18.k6")!=null;
        if(Phase18Cap1.enabled(p)){
            var usage=Phase18Cap1.output(p);
            Phase18Collector.sampleByteUpperBound(p);
            Phase18Plan.require(maxSampleBytes==Phase18Cap1.limit(67108864,usage.sampleBytes())
                &&maxTraceBytes==Phase18Cap1.limit(2097152,usage.traceBytes())
                &&maxTraceEntries==Phase18Cap1.limit(6000,usage.traceEntries()),"CAP1 frozen observation limits");
            Phase18Cap1.validate(p);
            return;
        }
        if(k6&&Phase18K6Session.expandedS2(p)){
            var usage=Phase18K6Session.s2Output(p);
            Phase18Collector.sampleByteUpperBound(p);
            Phase18Plan.require(maxSampleBytes==Phase18K6Session.expandLimit(67108864,usage.sampleBytes())
                    &&maxTraceBytes==Phase18K6Session.expandLimit(2097152,usage.traceBytes())
                    &&maxTraceEntries==Phase18K6Session.expandLimit(6000,usage.traceEntries()),"expanded S2 observation limits");
            Phase18Plan.require(p.bounds().samples()==Phase18K6Session.expandLimit(10000,usage.samples())
                    &&p.bounds().outputBytes()==Phase18K6Session.expandLimit(268435456,usage.runBytes()),"expanded S2 combined output");
            return;
        }
        long reservation=Math.addExact(Math.multiplyExact(2,Math.addExact(maxSampleBytes,maxTraceBytes)),
                k6?Phase18K6Session.outputBytes(p):Math.addExact(p.bounds().outputBytes()/4,p.counts(c).diskBytes()));
        Phase18Plan.require(reservation<=p.bounds().outputBytes(),"combined observation output reservation");
        if(k6&&Phase18K6Session.s2(p)){
            // 기존 기간 상한에는 build 등 비수집 구간도 포함. initial/recovery 각각에 보수적으로 적용
            long samples=p.counts(c).samples();
            Phase18Plan.require(samples<=p.bounds().samples()&&p.bounds().samples()<=10_000,"S2 observation sample count");
            Phase18Plan.require(maxSampleBytes>=Math.multiplyExact(samples,Phase18Collector.sampleByteUpperBound(p)),
                    "S2 observation lifetime bytes");
            // 현재 무작업 flush의 capture/retention/delete/flush 반환도 보존 대상. 수명 상한 미지원은 실행 전 거부
            Phase18Plan.require(Math.multiplyExact(p.counts(c).seconds(),4)<=maxTraceEntries,
                    "S2 conservative trace lifetime not supported");
        }
    }
}
