package dev.cgt.benchmark;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 이번 plan에 hash로 묶인 E18 현장 증거. 이전 PID/live proof 재사용과 3306 fallback을 실행 전에 차단 */
record Phase18Environment(int schemaVersion, String sessionId, String owner, String mysqlRoot, String mysqlUuid,
        long mysqlPid, String mysqlStartUtc, String mysqlBinary, String mysqlBinaryHash, String mysqlConfigHash,
        long redisPid, String redisStartUtc, String redisRunId, String catalogMode, String precreatedOwner, long mysqlBaselineBytes,
        long mysqlGrowthBytes, long evidenceBytes, long minimumFreeBytes, String observedUtc) {
    static Phase18Environment read(Path file, Phase18Plan plan) throws Exception {
        Phase18Paths.execution(file.toString()); Phase18Plan.require(Files.size(file) <= 32768, "environment bytes");
        var e = Phase18Plan.JSON.readValue(Files.readString(file), Phase18Environment.class);
        Phase18Plan.require(e.schemaVersion == 1 && e.sessionId.equals(plan.sessionId()) && e.owner.equals(plan.ownership().owner())
                && plan.ownership().dbInstance().equals(e.mysqlUuid + "@" + BenchmarkJson.hash(file)), "frozen environment identity");
        Phase18Plan.require(Instant.parse(e.observedUtc).plusSeconds(86400).isAfter(Instant.now()), "environment evidence age");
        long storageReservation=Phase18Cap1.enabled(plan)?Phase18Cap1.mysqlLimit(plan):Math.addExact(plan.bounds().databaseBytes(),plan.bounds().binlogBytes());
        Phase18Plan.require(e.mysqlGrowthBytes > 0 && e.mysqlGrowthBytes <= (plan.phase().equals("A")?2L*1024*1024*1024:storageReservation)
                && e.evidenceBytes > 0 && e.evidenceBytes <= (Phase18Cap1.enabled(plan)?Phase18Cap1.limit(335544320,Phase18Cap1.output(plan).evidenceBytes()):Phase18K6Session.expandedS2(plan)?Phase18K6Session.expandLimit(335544320,Phase18K6Session.s2Output(plan).evidenceBytes()):plan.phase().equals("A")?64L*1024*1024:Math.addExact(plan.bounds().outputBytes(),64L*1024*1024))
                && e.minimumFreeBytes >= Math.max(10L*1024*1024*1024,plan.bounds().minimumFreeBytes()), "environment budget within frozen reservation");
        if(Phase18Cap1.enabled(plan))Phase18Plan.require(e.mysqlGrowthBytes==storageReservation,"CAP1 MySQL reservation");
        requireEvidenceReservation(plan,e.evidenceBytes);
        Phase18Plan.require(Set.of("create-new","precreated-empty").contains(e.catalogMode), "catalog creation mode");
        e.current(plan); return e;
    }
    static void requireEvidenceReservation(Phase18Plan plan,long evidenceBytes){
        if(Phase18Cap1.enabled(plan)){
            Phase18Plan.require(evidenceBytes==Phase18Cap1.limit(335544320,Phase18Cap1.output(plan).evidenceBytes()),"CAP1 environment evidence reservation");
            return;
        }
        if(System.getProperty("phase18.k6")!=null&&Phase18K6Session.s2(plan))
            Phase18Plan.require(evidenceBytes==(Phase18K6Session.expandedS2(plan)?Phase18K6Session.expandLimit(335544320,Phase18K6Session.s2Output(plan).evidenceBytes()):Math.addExact(plan.bounds().outputBytes(),64L*1024*1024)),
                    "S2 environment evidence reservation");
    }
    void current(Phase18Plan plan) throws Exception {
        Phase18Plan.require(System.getProperty("user.name").equals(owner) && Runtime.version().feature() == 21
                && Path.of(System.getProperty("java.home"),"bin/java.exe").equals(Path.of(plan.ownership().java())), "actual launcher/owner");
        process(mysqlPid,mysqlStartUtc,mysqlBinary);
        // Windows 서비스 계정 Redis는 ProcessHandle 접근 불가. 실제 연결의 INFO run_id/process_id를 live guard에서 exact 확인
        Phase18Plan.require(redisRunId.matches("[0-9a-f]{40}")&&redisPid>0,"Redis instance identity");
        Path root=Phase18Paths.execution(mysqlRoot);
        Phase18Plan.require(BenchmarkJson.hash(Phase18Paths.execution(mysqlBinary)).equals(mysqlBinaryHash)
                && BenchmarkJson.hash(root.resolve("runtime.ini")).equals(mysqlConfigHash), "MySQL binary/config changed");
        budget(plan);
    }
    static void process(long pid,String start,String binary) {
        var p=ProcessHandle.of(pid).orElseThrow(()->new IllegalStateException("Owned service absent"));
        Phase18Plan.require(p.isAlive() && p.info().startInstant().orElseThrow().toEpochMilli()==Instant.parse(start).toEpochMilli(), "service PID/start identity");
        if(binary!=null)Phase18Plan.require(Path.of(p.info().command().orElseThrow()).equals(Path.of(binary)),"service executable");
    }
    void budget(Phase18Plan plan) throws Exception {
        for(Path p:List.of(Path.of(plan.ownership().evidenceRoot()),Path.of(plan.ownership().sessionRoot()),Path.of(mysqlRoot)))
            Phase18Plan.require(Phase18Paths.usable(p)>=minimumFreeBytes,"live free space");
        Phase18Plan.require(BenchmarkPaths.bytes(Path.of(mysqlRoot))-mysqlBaselineBytes<=mysqlGrowthBytes,"MySQL growth bound");
        Phase18Plan.require(BenchmarkPaths.bytes(Path.of(plan.ownership().evidenceRoot()))<=evidenceBytes
                && BenchmarkPaths.bytes(Path.of(plan.ownership().sessionRoot()))<=plan.bounds().buildBytes(),"evidence/build budget");
        if(System.getProperty("phase18.k6")!=null){
            Path run=Path.of(plan.ownership().evidenceRoot(),plan.cases().getFirst().runId());
            Phase18Plan.require(!Files.exists(run)||BenchmarkPaths.bytes(run)<=plan.bounds().outputBytes(),"k6 total output budget");
        }
        walBudget(plan);
        if(System.getProperty("phase18.k6")!=null){
            long evidenceNow=BenchmarkPaths.bytes(Path.of(plan.ownership().evidenceRoot()));
            long workNow=BenchmarkPaths.bytes(Path.of(plan.ownership().sessionRoot()));
            long mysqlNow=Math.max(0,BenchmarkPaths.bytes(Path.of(mysqlRoot))-mysqlBaselineBytes);
            Path walRoot=Phase18Paths.walRoot(plan.ownership().wal(),Path.of(plan.ownership().repo()),plan.sessionId());
            long walNow=Files.exists(walRoot)?BenchmarkPaths.bytes(walRoot):0;
            long evidencePeak=Phase18Cap1.enabled(plan)?Phase18Cap1.output(plan).evidenceBytes():Phase18K6Session.expandedS2(plan)?Phase18K6Session.s2Output(plan).evidenceBytes():evidenceBytes;
            long futureEvidence=Math.max(0,evidencePeak-evidenceNow)+64L*1024*1024;
            long futureF=Math.max(0,(Phase18Cap1.enabled(plan)?Phase18Cap1.workspaceUsage(plan):plan.bounds().buildBytes())-workNow)+Math.max(0,(Phase18Cap1.enabled(plan)?Phase18Cap1.mysqlUsage(plan):mysqlGrowthBytes)-mysqlNow)+Math.max(0,(Phase18Cap1.enabled(plan)?Phase18Cap1.walUsage(plan):plan.bounds().walBytes())-walNow)+5L*1024*1024*1024+futureEvidence;
            Phase18Plan.require(Phase18Paths.usable(Path.of(plan.ownership().evidenceRoot()))-futureF>=minimumFreeBytes
                &&Phase18Paths.usable(Path.of(plan.ownership().sessionRoot()))-futureF>=minimumFreeBytes,"k6 future peak and next reservation");
        }
    }
    /** 실제 WAL session 전체 사용량과 해당 volume 여유를 매번 확인. DB/build 볼륨 확인으로 대체 금지 */
    static void walBudget(Phase18Plan plan) throws Exception {
        Path root=Phase18Paths.walRoot(plan.ownership().wal(),Path.of(plan.ownership().repo()),plan.sessionId());
        Phase18Plan.require(Phase18Paths.store(root).type().equalsIgnoreCase("NTFS"),"supported WAL NTFS");
        Phase18Plan.require(Phase18Paths.usable(root)>=plan.bounds().minimumFreeBytes(),"live WAL free space");
        Phase18Plan.require(!Files.exists(root)||BenchmarkPaths.bytes(root)<=plan.bounds().walBytes(),"WAL session bound");
    }
}
