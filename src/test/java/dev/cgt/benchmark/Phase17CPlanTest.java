package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 완전한 plan guard의 실제 경로 검사. 작은 빈 소유 입력만 만들며 앱·DB/WAL record 생성 없음 */
class Phase17CPlanTest {
    @TempDir Path temporary;
    @Test void completeGuardAcceptsBomPlanAndSeparateDatabaseRootButRejectsExistingOutputAndPathTampering() throws Exception {
        Path source=Path.of("").toAbsolutePath();String run="phase17-C-guard-"+UUID.randomUUID();Path walRoot=source.resolve("data").resolve(run+"-wal");
        Files.createDirectory(walRoot);Path mysql=Files.createDirectory(temporary.resolve("mysql"));Path cp=Files.createDirectories(temporary.resolve("build/classes/benchmark"));Path temp=Files.createDirectory(temporary.resolve("temp"));Files.createDirectory(temporary.resolve("app"));
        var cases=new HashMap<String,Object>();for(String c:Phase17CPlan.CASES)cases.put(c,Map.of("mode",c.split("-")[0],"wal",walRoot.resolve(c+"/pixel-place.wal").toString(),"catalog","pixel_place_bench_guard_"+c.replace('-','_'),"users",c.endsWith("unflushed")?2:65,"segmentBytes",c.endsWith("unflushed")?1:1024));
        var map=new LinkedHashMap<String,Object>();map.put("sourceRoot",source.toString());map.put("runId",run);map.put("walRoot",walRoot.toString());map.put("outputRoot",temporary.toString());map.put("mysqlRoot",mysql.toString());map.put("cases",cases);map.put("redisDatabase",2);map.put("productionRedisDatabase",0);map.put("redisHost","127.0.0.1");map.put("mysqlPort",3307);map.put("mysqlHost","127.0.0.1");
        map.put("smoke",Map.of("seed",17003,"loadCase","spread-tiles","requests",65,"offeredPerSecond",8,"inFlight",4,"measurement",true,"websocketSessions",0,"stages",List.of(1,32,32)));
        map.put("deadlines",Map.of("ready",120,"drain",180,"shutdown",120,"databasePrepare",300,"request",15,"smoke",900));
        map.put("budget",Map.of("outputBytes",10000000,"walTotalBytes",4194304,"databaseBaselineBytes",0,"databaseGrowthBytes",10000000,"minimumEFreeBytes",1,"minimumCFreeBytes",1));
        var json=(ObjectNode)BenchmarkJson.MAPPER.valueToTree(map);Path plan=temporary.resolve("plan.json");
        var old=new HashMap<String,String>();for(String p:List.of("phase17c.output","java.io.tmpdir","jna.tmpdir","java.class.path"))old.put(p,System.getProperty(p));
        try {
            System.setProperty("phase17c.output",temporary.resolve("app/new").toString());System.setProperty("java.io.tmpdir",temp.toString());System.setProperty("jna.tmpdir",temp.toString());System.setProperty("java.class.path",cp.toString());
            Files.writeString(plan,"\uFEFF"+json);String[] args={"--plan",plan.toString(),"--case","single-smoke","--action","smoke"};
            assertDoesNotThrow(()->Phase17CPlan.read(args));assertFalse(Files.exists(temporary.resolve("app/new")));
            Files.createDirectory(temporary.resolve("app/new"));assertThrows(IllegalArgumentException.class,()->Phase17CPlan.read(args));Files.delete(temporary.resolve("app/new"));
            ((ObjectNode)json.path("cases").path("single-smoke")).put("wal",mysql.resolve("pixel-place.wal").toString());Files.writeString(plan,json.toString());assertThrows(IllegalArgumentException.class,()->Phase17CPlan.read(args));
            ((ObjectNode)json.path("cases").path("single-smoke")).put("wal",walRoot.resolve("single-smoke/pixel-place.wal").toString());
            ((ObjectNode)json.path("smoke")).put("requests",66);Files.writeString(plan,json.toString());assertThrows(IllegalArgumentException.class,()->Phase17CPlan.read(args));
            assertThrows(IllegalArgumentException.class,()->Phase17CPlan.owned(temporary.resolve("not-mysql/table.ibd"),mysql));
            try(var entries=Files.list(walRoot)){assertEquals(0,entries.count());}
        } finally {for(var e:old.entrySet()){if(e.getValue()==null)System.clearProperty(e.getKey());else System.setProperty(e.getKey(),e.getValue());}Files.delete(walRoot);}
    }
    @Test void existingCatalogRejectsBeforeAnyCreateOrSchemaStatement() throws Exception {
        var c=mock(Connection.class);var query=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(query);when(query.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true);when(rows.getLong(1)).thenReturn(1L);
        assertThrows(IllegalStateException.class,()->Phase17CFixtures.requireNewCatalog(c,"pixel_place_bench_existing"));verify(c,never()).createStatement();verify(c,never()).commit();verify(query).close();
        when(rows.getLong(1)).thenReturn(0L);assertDoesNotThrow(()->Phase17CFixtures.requireNewCatalog(c,"pixel_place_bench_new"));
    }
}
