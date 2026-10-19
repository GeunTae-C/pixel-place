package dev.cgt.benchmark;

/** CAP-1의 준비·출력 예약 산식. CAP-0 이력 및 다른 단계의 한도는 보존 */
final class Phase18Cap1 {
    private static final long MIB=1024L*1024;
    static final int MAX_TERMINALS=320_000, MAX_EVENTS=320_000, MAX_DELIVERIES=32_000_000;
    static final long ANALYSIS_MEMORY=1024*MIB, TIMING_MEMORY=256*MIB, RECORD_BYTES=1280*MIB;
    static boolean enabled(Phase18Plan p){return Phase18Cap.enabled(p)&&p.stage().equals("CAP-1")&&p.bounds().samples()>10000;}
    static boolean revised(Phase18Plan p){return enabled(p)&&p.schemaVersion()==4;}
    static boolean comparison(Phase18Plan p){
        return revised(p)&&p.cases().getFirst().role().equals("cap-diagnostic-ab");
    }
    private static void validateComparison(Phase18Plan p){
        var w=p.cases().getFirst().workload();
        Phase18Plan.require(w.writeRate()==105&&(w.measurementSeconds()==120||w.measurementSeconds()==1200)
                ||w.writeRate()==500&&w.measurementSeconds()==60,"finite diagnostic A/B combinations");
        try {
            var policy=Phase18Plan.JSON.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(p.predecessor().path())));
            var comparison=policy.path("comparison");
            Phase18Plan.require(policy.path("role").asText().equals("cap-diagnostic-ab")
                &&policy.path("instruction").path("sha256").asText().equals("b892240cf15c9cd4ff2f80fe1e86841bcc04680ff877938d2eba010a41cb8859")
                &&java.util.Set.of("A","B").contains(comparison.path("variant").asText())
                &&comparison.path("slot").asInt()>0&&comparison.path("slot").asInt()<=8
                &&policy.path("cap1Profile").path("role").asText().equals("cap-diagnostic-ab")
                &&policy.path("cap1Profile").path("writeRate").asLong()==w.writeRate()
                &&policy.path("cap1Profile").path("measurementSeconds").asLong()==w.measurementSeconds(),
                "diagnostic instruction/profile/slot predecessor binding");
        } catch(java.io.IOException e){throw new IllegalArgumentException("Diagnostic predecessor unavailable",e);}
    }
    static long users(Phase18Plan.Workload w){return Math.addExact(Phase18Cap.pool(w),1);}
    static long analysisSeconds(Phase18Plan.Workload w){
        long deliveries=Math.multiplyExact(Math.multiplyExact(w.writeRate(),Math.addExact(30,w.measurementSeconds())),100);
        return Math.max(120,Math.floorDiv(Math.addExact(deliveries,24999),25000));
    }
    static long seconds(Phase18Plan.Workload w){
        long preparation=Math.addExact(300,Math.floorDiv(Math.addExact(users(w),49),50));
        return Math.addExact(preparation,Math.addExact(w.measurementSeconds(),Math.addExact(3527,analysisSeconds(w))));
    }
    static Phase18K6Session.S2Output output(Phase18Plan p){
        var w=p.cases().getFirst().workload();
        Phase18Plan.require(enabled(p)&&w.writeRate()>=105&&w.writeRate()<=10000
            &&w.readRate()==0&&w.wsConnections()==100&&w.bootstrap()&&w.seed()==15
            &&w.writeInFlight()==128&&w.readInFlight()==4&&w.readyMillis()==90000
            &&w.drainMillis()==180000&&p.cases().getFirst().observation().sampleMillis()==250,
            "CAP1 frozen W-SPREAD profile");
        long events=Math.multiplyExact(w.writeRate(),Math.addExact(30,w.measurementSeconds()));
        long requests=Math.addExact(events,1),deliveries=Math.multiplyExact(events,100);
        long life=Math.addExact(seconds(w),180),samples=Math.addExact(2,Math.multiplyExact(life,4));
        long entries=Math.addExact(Math.multiplyExact(4,life),2048);
        long trace=Math.addExact(Math.multiplyExact(Math.multiplyExact(4,life),512),2048*2048+512*1024);
        long metrics=Math.addExact(96*MIB,Math.addExact(Math.multiplyExact(requests,65536),Math.multiplyExact(deliveries,128)));
        long bitsets=Math.multiplyExact(Math.multiplyExact(Math.floorDiv(Math.addExact(events,63),64),8),100);
        long disk=2*MIB+Math.multiplyExact(requests,2560)+Math.multiplyExact(events,256)+bitsets+100*1024
            +Math.multiplyExact(samples,1024)+Math.multiplyExact(deliveries,revised(p)?216:72);
        long sampleBytes=Math.multiplyExact(samples,6144);
        long run=Math.addExact(metrics,Math.addExact(disk,Math.addExact(Math.multiplyExact(requests,4096),
            48*MIB+Math.multiplyExact(2,Math.addExact(sampleBytes,Math.addExact(trace,MIB))))));
        return new Phase18K6Session.S2Output(samples,sampleBytes,entries,trace,trace+MIB,metrics,run,run+64*MIB);
    }
    static long limit(long base,long usage){return Phase18K6Session.expandLimit(base,usage);}
    static long databaseUsage(Phase18Plan p){
        var w=p.cases().getFirst().workload();long requests=p.counts(p.cases().getFirst()).writes();
        return 128*MIB+users(w)*4096+requests*4096+Math.min(requests-1,1024)*131072;
    }
    static long binlogUsage(Phase18Plan p){
        return 128*MIB+users(p.cases().getFirst().workload())*4096+p.counts(p.cases().getFirst()).writes()*(131072+4096);
    }
    static long mysqlUsage(Phase18Plan p){return databaseUsage(p)+binlogUsage(p)+1024*MIB;}
    static long walUsage(Phase18Plan p){return MIB+p.counts(p.cases().getFirst()).writes()*512;}
    static long workspaceUsage(Phase18Plan p){var n=p.counts(p.cases().getFirst());return 2048*MIB+n.writes()*4096+n.wsDeliveries()*(revised(p)?216:72);}
    static long physicalF(Phase18Plan p){return workspaceUsage(p)+mysqlUsage(p)+walUsage(p)+output(p).evidenceBytes()+64*MIB+5L*1024*MIB;}
    static long remainingPhysicalF(Phase18Plan p,long work,long mysql,long wal,long evidence){
        Phase18Plan.require(work>=0&&mysql>=0&&wal>=0&&evidence>=0,"CAP1 nonnegative consumed storage");
        return Math.max(0,workspaceUsage(p)-work)+Math.max(0,mysqlUsage(p)-mysql)
            +Math.max(0,walUsage(p)-wal)+Math.max(0,output(p).evidenceBytes()-evidence)+64*MIB+5L*1024*MIB;
    }
    static long mysqlLimit(Phase18Plan p){return limit(2048*MIB,databaseUsage(p)+binlogUsage(p)+1024*MIB);}
    static void validate(Phase18Plan p){
        if(comparison(p))validateComparison(p);
        var w=p.cases().getFirst().workload();var s=w.schedule();var out=output(p);
        Phase18Plan.require(w.writePattern().equals("spread-tiles")&&w.userPool()==users(w)-1
            &&p.bounds().totalSeconds()==seconds(w)&&p.bounds().totalSeconds()<21570
            &&Math.subtractExact(s.expiresEpochMillis(),s.issuedEpochMillis())==Math.multiplyExact(seconds(w),1000),
            "CAP1 frozen original reservation");
        Phase18Plan.require(p.bounds().walBytes()==limit(4*MIB,MIB+p.counts(p.cases().getFirst()).writes()*512)
            &&p.bounds().databaseBytes()==limit(1024*MIB,databaseUsage(p))
            &&p.bounds().binlogBytes()==limit(1024*MIB,binlogUsage(p)),"CAP1 frozen DB/WAL reservation");
        Phase18Plan.require(p.bounds().samples()==limit(10000,out.samples())
            &&p.bounds().outputBytes()==limit(256*MIB,out.runBytes())&&p.bounds().buildBytes()==limit(2048*MIB,workspaceUsage(p)),"CAP1 frozen output reservation");
    }
}
