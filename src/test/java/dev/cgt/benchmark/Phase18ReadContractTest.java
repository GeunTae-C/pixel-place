package dev.cgt.benchmark;

import dev.cgt.pixelplace.tile.domain.TileState;
import dev.cgt.pixelplace.tile.application.*;
import dev.cgt.pixelplace.tile.web.TileController;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 서버 read 계측의 투명성과 snapshot 버전·JWT 수명 경계. 최신 상태로 과거 응답을 거절하지 않음 */
class Phase18ReadContractTest {
    @Test void replayAcceptsPastVersionAndRejectsCorruptHashOrUnavailableVersion() throws Exception {
        byte[] white=TileState.allWhite().pixels(), one=white.clone(), two=white.clone();one[0]=17;two[0]=23;
        var events=List.of(new Phase18Events.Event(9,2,0,0,23),new Phase18Events.Event(2,1,0,0,17));
        Phase18ReadReplay.verify(List.of(new Phase18ReadReplay.Sample(0,0,1,Phase18TileBody.hash(one)),new Phase18ReadReplay.Sample(0,0,0,Phase18TileBody.hash(white))),events);
        assertThrows(IllegalStateException.class,()->Phase18ReadReplay.verify(List.of(new Phase18ReadReplay.Sample(0,0,1,Phase18TileBody.hash(two))),events));
        assertThrows(IllegalStateException.class,()->Phase18ReadReplay.verify(List.of(new Phase18ReadReplay.Sample(0,0,3,Phase18TileBody.hash(two))),events));
        assertTrue(java.util.stream.LongStream.range(0,200000).filter(i->Phase18ReadReplay.sampled(i,200000)).count()<=1000);
    }
    @Test void tileTimersOffAndObserverRuntimeFailureKeepHeadersBytesAndOriginalError() throws Exception {
        var service=mock(TileReadService.class);byte[] white=TileState.allWhite().pixels();
        when(service.readTile(0,0,0)).thenReturn(new TileReadResult(white,7));
        var offRegistry=new SimpleMeterRegistry();var off=new TileController(service,new PixelMeasurement(false,offRegistry));
        var expected=off.getTile(0,0,0);assertTrue(offRegistry.getMeters().isEmpty());
        var registry=new SimpleMeterRegistry();var measurement=new PixelMeasurement(true,registry);
        var controller=new TileController(service,measurement);
        var timers=(Timer[][][])ReflectionTestUtils.getField(measurement,"timers");
        Timer broken=mock(Timer.class);doThrow(new IllegalStateException("observer")).when(broken).record(anyLong(),any(TimeUnit.class));
        for(var op:List.of(PixelMeasurement.Operation.tile_read,PixelMeasurement.Operation.tile_gzip))timers[op.ordinal()][0][0]=broken;
        var actual=controller.getTile(0,0,0);assertEquals(expected.getHeaders(),actual.getHeaders());assertArrayEquals(expected.getBody(),actual.getBody());assertEquals(2,measurement.observerFailures());
        var primary=new AssertionError("read");var secondary=new AssertionError("observer");
        when(service.readTile(0,0,0)).thenThrow(primary);Timer raw=mock(Timer.class);doThrow(secondary).when(raw).record(anyLong(),any(TimeUnit.class));
        timers[PixelMeasurement.Operation.tile_read.ordinal()][0][PixelMeasurement.Outcome.failure.ordinal()]=raw;
        assertSame(primary,assertThrows(AssertionError.class,()->controller.getTile(0,0,0)));assertArrayEquals(new Throwable[]{secondary},primary.getSuppressed());
    }
    @Test void tokenCacheRefreshesBeforeExpiryAndNeverGrowsWithAllUsers() {
        class MovingClock extends Clock { Instant time=Instant.parse("2026-09-30T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return time;} }
        var clock=new MovingClock();String key=Base64.getEncoder().encodeToString(new byte[32]);byte[] second=new byte[32];second[0]=1;
        var auth=new AuthProperties(key,Base64.getEncoder().encodeToString(second),Duration.ofMinutes(15));
        var tokens=new Phase18Tokens(new ServiceJwtTokens(auth,clock),clock,2);String first=tokens.get(1);assertEquals(first,tokens.get(1));
        clock.time=clock.time.plusSeconds(811);assertNotEquals(first,tokens.get(1));assertEquals(2L,tokens.report().get("issued"));
        tokens.get(2);tokens.get(3);assertEquals(2,tokens.report().get("cached"));
    }
}
