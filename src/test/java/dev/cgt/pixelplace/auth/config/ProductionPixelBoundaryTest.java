package dev.cgt.pixelplace.auth.config;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.pixel.application.*;
import dev.cgt.pixelplace.pixel.infra.RedisPixelCooldown;
import dev.cgt.pixelplace.pixel.web.PixelController;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.recovery.web.*;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.wal.application.WalAppender;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import jakarta.servlet.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** production chain→controller→실제 command/core/Redis adapter의 인접 ID와 readiness·실패 경계. WAL 저장은 별도 MySQL 연결 검증 */
class ProductionPixelBoundaryTest {
    @TestConfiguration(proxyBeanMethods=false)
    @Import({PixelController.class,ReadinessGuardInterceptor.class,ReadinessWebConfig.class,ServiceNotReadyExceptionHandler.class})
    static class Fixture {
        @Bean Assembly assembly(){return new Assembly();}
        @Bean PixelCommandService command(Assembly a){return a.command;}
        @Bean ServiceReadiness readiness(Assembly a){return a.readiness;}
    }
    static class Assembly {
        final ServiceReadiness readiness=new ServiceReadiness();
        final StringRedisTemplate redis=mock(StringRedisTemplate.class);
        final ValueOperations<String,String> values=mock(ValueOperations.class);
        final RedisPixelCooldown cooldown=spy(new RedisPixelCooldown(redis));
        final EventSeqManager sequence=spy(new EventSeqManager());
        final WalAppender wal=mock(WalAppender.class);
        final InMemoryTileBoard board=spy(new InMemoryTileBoard());
        final DirtyTileTracker dirty=mock(DirtyTileTracker.class);
        final PixelBroadcastService broadcast=mock(PixelBroadcastService.class);
        final PixelWriteService core=spy(new PixelWriteService(sequence,wal,board,readiness, dev.cgt.pixelplace.measurement.Measurements.disabled()));
        final PixelUserWriteGate gate = new PixelUserWriteGate();
        final PixelCommandService command=spy(new PixelCommandService(cooldown,
                new dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutor(new FlushBoundaryCoordinator(dev.cgt.pixelplace.measurement.Measurements.disabled()), core, dirty, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled()),
                broadcast,
                readiness,
                gate,
                dev.cgt.pixelplace.measurement.Measurements.disabled()));
        Assembly(){when(redis.opsForValue()).thenReturn(values);readiness.markReady();}
        void clear(){clearInvocations(command,core,cooldown,sequence,wal,board,dirty,broadcast,redis,values);}
    }
    @Test
    // production 인증→실제 gate interrupt→controller 503이며 Redis/core 진입 없음
    void authenticatedBusyWriteMapsTo503WithoutInventedCooldownTtl() {
        ProductionAuthTestSupport.runner().withUserConfiguration(Fixture.class).run(c -> {
            var mvc = MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var a = c.getBean(Assembly.class); a.clear();
            var token = c.getBean(ServiceJwtTokens.class).issueAccess(42).getTokenValue();
            Thread.currentThread().interrupt();
            try {
                mvc.perform(post("/api/pixels").header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"x\":1,\"y\":2,\"color\":3}"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.message").value(PixelWriteBusyException.MESSAGE))
                        .andExpect(jsonPath("$.remainingMillis").doesNotExist());
                assertTrue(Thread.currentThread().isInterrupted());
                verifyNoInteractions(a.redis, a.core, a.sequence, a.wal, a.board, a.dirty, a.broadcast);
            } finally { Thread.interrupted(); }
        });
    }
    @Test void verifiedInternalIdFlowsThroughCommandRedisAndWalDespiteAttackerHeader() {
        ProductionAuthTestSupport.runner().withUserConfiguration(Fixture.class).run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var a=c.getBean(Assembly.class); a.clear();
            var token=c.getBean(ServiceJwtTokens.class).issueAccess(42).getTokenValue();
            mvc.perform(post("/api/pixels").header("Authorization","Bearer "+token).header("X-User-Id","999")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"x\":1,\"y\":2,\"color\":3,\"userId\":999}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
            var record=org.mockito.ArgumentCaptor.forClass(WalRecord.class);
            var order=inOrder(a.command,a.cooldown,a.core,a.wal,a.board,a.dirty);
            order.verify(a.command).writePixel(42,1,2,3);order.verify(a.cooldown).checkWritable(42);
            order.verify(a.core).writePixel(42,1,2,3);order.verify(a.wal).appendAndFsync(record.capture());
            order.verify(a.board).applyPixel(1,2,3);order.verify(a.dirty).markDirty(any(),eq(1L),eq(1L));
            assertEquals(42,record.getValue().userId());
            verify(a.redis).getExpire("cooldown:user:42",TimeUnit.MILLISECONDS);
            verify(a.values).set("cooldown:user:42","1",Duration.ofSeconds(180));
            verify(a.redis,never()).getExpire("cooldown:user:999",TimeUnit.MILLISECONDS);
        });
    }
    @Test void authenticationPrecedesReadinessAndOnlyHeaderBearerCanAuthenticate() {
        ProductionAuthTestSupport.runner().withUserConfiguration(Fixture.class).run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build(); var a=c.getBean(Assembly.class);
            var token=c.getBean(ServiceJwtTokens.class).issueAccess(42).getTokenValue();
            a.readiness.markNotReady();a.clear();
            mvc.perform(post("/api/pixels").contentType(MediaType.APPLICATION_JSON).content("{\"x\":1,\"y\":2,\"color\":3}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/pixels").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"x\":1,\"y\":2,\"color\":3}"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("Service is not ready."));
            mvc.perform(get("/api/board")).andExpect(status().isServiceUnavailable());
            mvc.perform(get("/api/board").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
            a.readiness.markReady();
            mvc.perform(post("/api/pixels").param("access_token",token).cookie(new jakarta.servlet.http.Cookie("access_token",token))
                            .header("X-User-Id","42").contentType(MediaType.APPLICATION_JSON).content("{\"x\":1,\"y\":2,\"color\":3}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/pixels").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("access_token",token))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(a.command,a.core,a.wal,a.dirty,a.redis);
        });
    }
    @Test void missingFieldsStopAtControllerAndRangeErrorsStopAfterRedisBeforeExecutor() {
        ProductionAuthTestSupport.runner().withUserConfiguration(Fixture.class).run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();var a=c.getBean(Assembly.class);
            var token=c.getBean(ServiceJwtTokens.class).issueAccess(42).getTokenValue();a.clear();
            for(String body:new String[]{"{\"y\":2,\"color\":3}","{\"x\":1,\"color\":3}","{\"x\":1,\"y\":2}"})
                mvc.perform(post("/api/pixels").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            verifyNoInteractions(a.command,a.core,a.wal,a.redis);
            for(int[] v:new int[][]{{-1,2,3},{8192,2,3},{1,-1,3},{1,8192,3},{1,2,-1},{1,2,256}}) {
                a.clear();
                mvc.perform(post("/api/pixels").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"x\":"+v[0]+",\"y\":"+v[1]+",\"color\":"+v[2]+"}")).andExpect(status().isBadRequest());
                verify(a.command).writePixel(42,v[0],v[1],v[2]);verifyNoInteractions(a.core);
                verify(a.cooldown).checkWritable(42);verify(a.cooldown,never()).startCooldown(anyLong());
                verifyNoInteractions(a.sequence,a.wal,a.board,a.dirty,a.broadcast,a.values);
            }
        });
    }
    @Test void cooldownFailuresAndFirstFatalKeepExistingHttpMeaningThenReadinessBlocksFollowingWrite() {
        ProductionAuthTestSupport.runner().withUserConfiguration(Fixture.class).run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();var a=c.getBean(Assembly.class);
            var token=c.getBean(ServiceJwtTokens.class).issueAccess(42).getTokenValue();
            var request=post("/api/pixels").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"x\":1,\"y\":2,\"color\":3}");
            when(a.redis.getExpire(anyString(),eq(TimeUnit.MILLISECONDS))).thenReturn(123L);
            mvc.perform(request).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.remainingMillis").value(123));
            when(a.redis.getExpire(anyString(),eq(TimeUnit.MILLISECONDS))).thenThrow(new IllegalStateException("test-redis-failure"));
            mvc.perform(request).andExpect(status().isServiceUnavailable());
            doReturn(-2L).when(a.redis).getExpire(anyString(),eq(TimeUnit.MILLISECONDS));
            doThrow(new IllegalStateException("test-wal-failure")).when(a.wal).appendAndFsync(any());
            var failure=assertThrows(ServletException.class,()->mvc.perform(request));
            assertInstanceOf(IllegalStateException.class,failure.getCause());assertFalse(a.readiness.isReady());
            mvc.perform(get("/error").with(r->{r.setDispatcherType(DispatcherType.ERROR);r.setAttribute(RequestDispatcher.ERROR_STATUS_CODE,500);return r;}))
                    .andExpect(status().isInternalServerError());
            a.clear();mvc.perform(request).andExpect(status().isServiceUnavailable());verifyNoInteractions(a.command);
        });
    }
}
