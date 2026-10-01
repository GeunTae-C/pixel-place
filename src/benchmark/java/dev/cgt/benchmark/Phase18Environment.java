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
        Phase18Paths.checked(file.toString()); Phase18Plan.require(Files.size(file) <= 32768, "environment bytes");
        var e = Phase18Plan.JSON.readValue(Files.readString(file), Phase18Environment.class);
        Phase18Plan.require(e.schemaVersion == 1 && e.sessionId.equals(plan.sessionId()) && e.owner.equals(plan.ownership().owner())
                && plan.ownership().dbInstance().equals(e.mysqlUuid + "@" + BenchmarkJson.hash(file)), "frozen environment identity");
        Phase18Plan.require(Instant.parse(e.observedUtc).plusSeconds(86400).isAfter(Instant.now()), "environment evidence age");
        long storageReservation=Math.addExact(plan.bounds().databaseBytes(),plan.bounds().binlogBytes());
        Phase18Plan.require(e.mysqlGrowthBytes > 0 && e.mysqlGrowthBytes <= (plan.phase().equals("A")?2L*1024*1024*1024:storageReservation)
                && e.evidenceBytes > 0 && e.evidenceBytes <= (plan.phase().equals("A")?64L*1024*1024:Math.addExact(plan.bounds().outputBytes(),64L*1024*1024))
                && e.minimumFreeBytes >= Math.max(10L*1024*1024*1024,plan.bounds().minimumFreeBytes()), "environment budget within frozen reservation");
        Phase18Plan.require(Set.of("create-new","precreated-empty").contains(e.catalogMode), "catalog creation mode");
        e.current(plan); return e;
    }
    void current(Phase18Plan plan) throws Exception {
        Phase18Plan.require(System.getProperty("user.name").equals(owner) && Runtime.version().feature() == 21
                && Path.of(System.getProperty("java.home"),"bin/java.exe").equals(Path.of(plan.ownership().java())), "actual launcher/owner");
        process(mysqlPid,mysqlStartUtc,mysqlBinary);
        // Windows 서비스 계정 Redis는 ProcessHandle 접근 불가. 실제 연결의 INFO run_id/process_id를 live guard에서 exact 확인
        Phase18Plan.require(redisRunId.matches("[0-9a-f]{40}")&&redisPid>0,"Redis instance identity");
        Path root=Phase18Paths.checked(mysqlRoot);
        Phase18Plan.require(BenchmarkJson.hash(Phase18Paths.checked(mysqlBinary)).equals(mysqlBinaryHash)
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
        Path wal=Phase18Paths.checked(plan.ownership().wal());
        Phase18Plan.require(wal.toString().substring(0,2).equalsIgnoreCase("C:")
                && Files.getFileStore(Path.of(plan.ownership().repo())).type().equalsIgnoreCase("NTFS"),"supported C NTFS WAL");
        Phase18Plan.require(!Files.exists(wal.getParent())||BenchmarkPaths.bytes(wal.getParent())<=plan.bounds().walBytes(),"WAL bound");
    }
}
