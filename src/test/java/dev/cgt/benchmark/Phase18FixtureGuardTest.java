package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 새 catalog의 선택/빈 상태와 같은 initial 복구 gate. 거부 원인을 보정용 seed로 숨기지 않는 경계 */
class Phase18FixtureGuardTest {
    @Test void recoveryGetsOnlyRemainingObservationTimeAndExpiredBudgetCannotStartChild() {
        var path=java.nio.file.Path.of("unused");var command=new Phase18Process.Command(List.of("java"),path,path,300000,30000,65536);
        assertEquals(10,Phase18Transport.withinDeadline(command,11000000,1000000).observationMillis());
        assertEquals(30000,Phase18Transport.withinDeadline(command,11000000,1000000).cleanupMillis());
        assertThrows(IllegalArgumentException.class,()->Phase18Transport.withinDeadline(command,1000000,1000000));
        assertThrows(IllegalArgumentException.class,()->Phase18Transport.withinDeadline(command,1000000,2000000));
    }
    @Test void exactCredentialsRejectDefaultPortAndWrongRedisBeforeConnection()throws Exception {
        var p=Phase18PlanTest.valid();
        var good=new BenchmarkEnvironment.Credentials("127.0.0.1",3307,"user","secret","127.0.0.1",6379,p.ownership().redisIndex(),"","");
        Phase18Fixtures.credentials(good,p);
        for(var bad:List.of(new BenchmarkEnvironment.Credentials("127.0.0.1",3306,"user","secret","127.0.0.1",6379,p.ownership().redisIndex(),"",""),
                new BenchmarkEnvironment.Credentials("localhost",3307,"user","secret","127.0.0.1",6379,p.ownership().redisIndex(),"",""),
                new BenchmarkEnvironment.Credentials("127.0.0.1",3307,"user","secret","127.0.0.1",6379,0,"","")))
            assertThrows(IllegalArgumentException.class,()->Phase18Fixtures.credentials(bad,p));
    }
    @Test void selectedCatalogMismatchAndAnyExistingObjectRejectBeforeDdl() throws Exception {
        String catalog=BenchmarkGuards.catalog("phase18_test");
        Connection c=mock(Connection.class);Statement select=mock(Statement.class);ResultSet database=mock(ResultSet.class);
        when(c.createStatement()).thenReturn(select);when(select.executeQuery("SELECT DATABASE()")).thenReturn(database);when(database.next()).thenReturn(true);when(database.getString(1)).thenReturn("pixel_place");
        assertThrows(IllegalStateException.class,()->Phase18Fixtures.empty(c,catalog));verify(c,never()).prepareStatement(anyString());
        when(database.getString(1)).thenReturn(catalog);
        PreparedStatement objects=mock(PreparedStatement.class);ResultSet count=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(objects);when(objects.executeQuery()).thenReturn(count);when(count.next()).thenReturn(true);when(count.getLong(1)).thenReturn(1L);
        assertThrows(IllegalArgumentException.class,()->Phase18Fixtures.empty(c,catalog));
        when(count.getLong(1)).thenReturn(0L);Phase18Fixtures.empty(c,catalog);
        verify(select,never()).execute(anyString());verify(objects,never()).executeUpdate();verify(objects,never()).executeBatch();
    }
    @Test void recoveryAcceptsStoppedSameInitialAndRejectsIdentityModeSegmentHashOrShutdown() throws Exception {
        var p=Phase18PlanTest.valid();var loaded=new Phase18Plan.Loaded(p,"a".repeat(64));var c=p.cases().getFirst();
        ObjectNode m=Phase18Plan.JSON.createObjectNode();m.put("complete",true);m.put("normalShutdown",true);m.put("initialAction","initial");m.put("planHash",loaded.planHash());
        m.put("inputHash",Phase18TileBody.hash(Phase18Plan.JSON.writeValueAsBytes(p.inputs())));
        m.put("catalog",p.ownership().catalog());m.put("wal",p.ownership().wal());m.put("mode",c.runtime().mode());m.put("segmentBytes",c.runtime().segmentBytes());m.put("environmentIdentity",p.ownership().dbInstance());m.put("pid",Long.MAX_VALUE);
        ObjectNode r=Phase18Plan.JSON.createObjectNode();r.put("exit",0);r.put("success",true);r.put("manifestHash","same");
        Phase18BenchmarkMain.requireInitial(m,r,loaded,"same");
        for(String key:List.of("catalog","wal","mode","planHash","inputHash","environmentIdentity","initialAction")){
            var bad=m.deepCopy();bad.put(key,"different");assertThrows(IllegalArgumentException.class,()->Phase18BenchmarkMain.requireInitial(bad,r,loaded,"same"),key);
        }
        var small=m.deepCopy();small.put("segmentBytes",1024);assertThrows(IllegalArgumentException.class,()->Phase18BenchmarkMain.requireInitial(small,r,loaded,"same"));
        var live=m.deepCopy();live.put("pid",ProcessHandle.current().pid());live.put("startUtc",ProcessHandle.current().info().startInstant().orElseThrow().toString());
        assertThrows(IllegalArgumentException.class,()->Phase18BenchmarkMain.requireInitial(live,r,loaded,"same"));
        var failed=m.deepCopy();failed.put("normalShutdown",false);assertThrows(IllegalArgumentException.class,()->Phase18BenchmarkMain.requireInitial(failed,r,loaded,"same"));
        r.put("exit",1);assertThrows(IllegalArgumentException.class,()->Phase18BenchmarkMain.requireInitial(m,r,loaded,"same"));
    }
}
