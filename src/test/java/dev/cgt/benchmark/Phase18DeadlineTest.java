package dev.cgt.benchmark;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 대표·SOAK 계획을 실제 command/Process 경계로 전달. 부하는 exit0 자식으로 제한 */
class Phase18DeadlineTest {
    @TempDir Path directory;
    static Phase18Plan base;
    @BeforeAll static void baseline() throws Exception { base=Phase18PlanTest.valid(); }
    static Phase18Plan plan(String stage,long seconds) throws Exception {
        var j=(ObjectNode)Phase18Plan.JSON.valueToTree(base);
        j.put("phase",stage);j.put("stage",stage);
        ((ObjectNode)j.path("bounds")).put("totalSeconds",seconds);
        var o=(ObjectNode)j.path("ownership");
        o.put("wal",Path.of(o.path("repo").asText(),"data",j.path("sessionId").asText(),"trial/pixel-place.wal").toString());
        o.put("catalog","pixel_place_bench_deadline");o.put("dbInstance","unopened-test");o.put("redisIndex",2);
        var c=(ObjectNode)j.path("cases").get(0);c.put("kind","mixed");c.put("role",stage.equals("D")?"soak":"target");
        var w=(ObjectNode)c.path("workload");
        w.put("writeRate",105);w.put("readRate",100);w.put("wsConnections",100);w.put("warmupSeconds",30);
        w.put("measurementSeconds",stage.equals("D")?1800:120);w.put("simultaneousSeconds",stage.equals("D")?1800:120);
        w.put("userMode","reuse");w.put("userPool",20000);w.put("bootstrap",true);
        w.put("readyMillis",120000);w.put("controlMillis",15000);w.put("drainMillis",60000);w.put("requestMillis",30000);
        var p=Phase18Plan.JSON.treeToValue(j,Phase18Plan.class);p.validate();return p;
    }
    static String classpath() throws Exception {
        var paths=new LinkedHashSet<String>();
        for(ClassLoader loader=Phase18Tools.class.getClassLoader();loader!=null;loader=loader.getParent())
            if(loader instanceof java.net.URLClassLoader urls)for(var url:urls.getURLs())paths.add(Path.of(url.toURI()).toString());
        return paths.isEmpty()?System.getProperty("java.class.path"):String.join(java.io.File.pathSeparator,paths);
    }
    @Test void representativeAndSoakCommandsPassActualProcessValidationAndShortJvmExit() throws Exception {
        String previous=System.getProperty("java.class.path");System.setProperty("java.class.path",classpath());
        try {
            for(String stage:List.of("B","D")) {
                var p=plan(stage,stage.equals("B")?600:2700);
                var cmd=Phase18Transport.command(p,directory.resolve(stage),directory.resolve(stage+".args"),"128m",Phase18FixtureChild.class.getName(),List.of("exit0"));
                assertEquals(stage.equals("B")?600000:2700000,cmd.observationMillis());
                assertEquals(0,Phase18Process.run(cmd,child->assertEquals("EXIT0",child.receive(5000))).exit());
            }
        } finally {System.setProperty("java.class.path",previous);}
        assertTrue(Phase18Process.unresolved().isEmpty());
    }
    @Test void invalidLimitsAndExpiredBudgetRejectBeforeStartingOrCreatingEvidence() throws Exception {
        for(long total:List.of(0L,-1L,86401L,Long.MAX_VALUE))assertThrows(Exception.class,()->plan("D",total));
        var p=plan("D",2700);
        for(long millis:List.of(0L,-1L,2700001L,Long.MAX_VALUE))
            assertThrows(IllegalArgumentException.class,()->Phase18Deadline.start(p,millis));
        for(String value:List.of("","0","-1","1.5",Long.toString(System.currentTimeMillis()-1),Long.toString(System.currentTimeMillis()+86400001)))
            assertThrows(IllegalArgumentException.class,()->Phase18Deadline.inherit(p,value));
        var args=List.of(p.ownership().java(),"-version");
        var budget=Phase18Deadline.start(p,2700000);
        for(var cmd:List.of(
                new Phase18Process.Command(args,Path.of(p.ownership().repo()),directory.resolve("fixture"),600001,1000,65536),
                new Phase18Process.Command(args,Path.of(p.ownership().repo()),directory.resolve("above-plan"),2700001,1000,65536,budget),
                new Phase18Process.Command(args,Path.of(p.ownership().repo()),directory.resolve("cleanup"),2700000,600001,65536,budget),
                new Phase18Process.Command(args,Path.of(p.ownership().repo()),directory.resolve("log"),2700000,1000,1048577,budget))) {
            assertThrows(IllegalArgumentException.class,()->Phase18Process.run(cmd,child->fail("must not start")));
            assertFalse(Files.exists(cmd.evidence()));
        }
        var j=(ObjectNode)Phase18Plan.JSON.valueToTree(p);j.put("phase","A");j.put("stage","A-2");
        var fixture=Phase18Plan.JSON.treeToValue(j,Phase18Plan.class);
        assertThrows(IllegalArgumentException.class,()->Phase18Deadline.start(fixture,2700000));
    }
    @Test void initialRecoveryAndGeneratorInheritSameExpiryAndCannotRestoreSpentTime() throws Exception {
        var p=plan("B",600);var budget=Phase18Deadline.start(p,15000);
        Path planFile=directory.resolve("inherited.json");BenchmarkJson.write(planFile,p);
        String previous=System.getProperty("java.class.path");System.setProperty("java.class.path",classpath());
        long previousRemaining=15001;
        try {
            for(String role:List.of("initial","generator","recovery")) {
                var cmd=Phase18Transport.command(p,directory.resolve(role),directory.resolve(role+".args"),"128m",DeadlineChild.class.getName(),List.of(planFile.toString()),budget);
                long[] remaining={0};
                Phase18Process.run(cmd,child->{
                    var fields=child.receive(10000).split(" ");
                    assertEquals(Long.toString(budget.expiresEpochMillis()),fields[0]);remaining[0]=Long.parseLong(fields[1]);
                });
                assertTrue(remaining[0]>0&&remaining[0]<previousRemaining);previousRemaining=remaining[0];
                assertSame(budget,Phase18Transport.withinDeadline(cmd,budget.deadlineNanos(),System.nanoTime()).execution());
            }
        } finally {System.setProperty("java.class.path",previous);}
        var cmd=new Phase18Process.Command(List.of(p.ownership().java()),Path.of(p.ownership().repo()),directory.resolve("spent"),600000,1000,65536,budget);
        assertThrows(IllegalArgumentException.class,()->Phase18Transport.withinDeadline(cmd,budget.deadlineNanos(),budget.deadlineNanos()));
        assertTrue(Phase18Process.unresolved().isEmpty());
    }
    /** 실제 JVM 안에서 production 상속 경계를 호출하는 부하 없는 자식 */
    public static final class DeadlineChild {
        public static void main(String[] args) throws Exception {
            var p=Phase18Plan.JSON.readValue(Files.readString(Path.of(args[0])),Phase18Plan.class);
            var inherited=Phase18Deadline.inherit(p);
            System.out.println(inherited.expiresEpochMillis()+" "+inherited.capMillis(600000));
        }
    }
}
