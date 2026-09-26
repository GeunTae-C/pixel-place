package dev.cgt.pixelplace.auth.config;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.measurement.*;
import dev.cgt.pixelplace.pixel.application.*;
import dev.cgt.pixelplace.pixel.web.PixelController;
import dev.cgt.pixelplace.recovery.application.*;
import dev.cgt.pixelplace.recovery.web.*;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.domain.*;
import dev.cgt.pixelplace.wal.application.WalAppender;
import jakarta.servlet.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 JWT/filter/MVC/command/DI group 연결. Redis 서비스/실제 WS 서버는 별도 연결 검증과 합산 */
class GroupPixelMvcTest {
    @TestConfiguration(proxyBeanMethods=false)
    @Import({WriteExecutionConfiguration.class,PixelWriteService.class,EventSeqManager.class,FlushBoundaryCoordinator.class,
            PixelCommandService.class,PixelController.class,PixelUserWriteGate.class,ReadinessGuardInterceptor.class,
            ReadinessWebConfig.class,ServiceNotReadyExceptionHandler.class})
    static class Fixture {
        @Bean PixelMeasurement measure(){return Measurements.disabled();}
        @Bean ServiceReadiness ready(){var ready=new ServiceReadiness();ready.markReady();return ready;}
        @Bean WalAppender wal(){return mock(WalAppender.class);}
        @Bean DirtyTileTracker dirty(){return mock(DirtyTileTracker.class);}
        @Bean PixelCooldown cooldown(){return mock(PixelCooldown.class);}
        @Bean PixelBroadcastService broadcast(){return mock(PixelBroadcastService.class);}
        @Bean InMemoryTileBoard board(){var board=mock(InMemoryTileBoard.class);var version=new AtomicLong();
            when(board.applyPixel(anyInt(),anyInt(),anyInt())).thenAnswer(c->new TileMutationResult(new TileKey(0,0,0),version.incrementAndGet()));return board;}
    }
    private org.springframework.boot.test.context.runner.WebApplicationContextRunner context(){
        return ProductionAuthTestSupport.runner().withUserConfiguration(Fixture.class)
                .withPropertyValues("pixel-place.write.mode=group","pixel-place.write.group.max-batch-size=2",
                        "pixel-place.write.group.max-outstanding=2","pixel-place.write.group.queue-timeout=100ms",
                        "pixel-place.write.group.shutdown-grace=30ms");
    }
    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String token,int color){
        return post("/api/pixels").header("Authorization","Bearer "+token).header("X-User-Id","999")
                .contentType(MediaType.APPLICATION_JSON).content("{\"x\":1,\"y\":2,\"color\":"+color+"}");
    }
    @Test void jwtInternalIdValidationCooldownAndOrdinaryPostprocessingFailuresKeepMeaning(){
        context().run(c->{
            assertNull(c.getStartupFailure());var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var token=c.getBean(ServiceJwtTokens.class).issueAccess(42).getTokenValue();
            var cooldown=c.getBean(PixelCooldown.class);var wal=c.getBean(WalAppender.class);var broadcast=c.getBean(PixelBroadcastService.class);
            mvc.perform(request(token,256)).andExpect(status().isBadRequest());verifyNoInteractions(wal);
            doThrow(new PixelCooldownActiveException(123)).when(cooldown).checkWritable(42);
            mvc.perform(request(token,3)).andExpect(status().isTooManyRequests());verifyNoInteractions(wal);
            doNothing().when(cooldown).checkWritable(42);
            doThrow(new PixelCooldownUnavailableException("redis",new IllegalStateException("redis"))).when(cooldown).startCooldown(42);
            doThrow(new IllegalStateException("ws")).when(broadcast).broadcast(any());
            mvc.perform(request(token,3)).andExpect(status().isOk()).andExpect(jsonPath("$.tileVersion").value(1));
            var records=org.mockito.ArgumentCaptor.forClass(java.util.List.class);verify(wal).appendBatchAndFsync(records.capture());
            assertEquals(42,((dev.cgt.pixelplace.wal.domain.WalRecord)records.getValue().getFirst()).userId());
            verify(cooldown).startCooldown(42);verify(broadcast).broadcast(any());
        });
    }
    @Test void actualQueueTimeoutIsBusyAndGraceUnknownIsDistinct503WithoutRetryHeader(){
        context().run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();var tokens=c.getBean(ServiceJwtTokens.class);
            var firstToken=tokens.issueAccess(42).getTokenValue();var secondToken=tokens.issueAccess(43).getTokenValue();
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            doAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;}).when(c.getBean(WalAppender.class)).appendBatchAndFsync(any());
            var executor=c.getBean(PixelWriteExecutor.class);
            try(var callers=Executors.newFixedThreadPool(2)) {try {
                var first=callers.submit(()->mvc.perform(request(firstToken,3)));assertTrue(entered.await(5,TimeUnit.SECONDS));
                mvc.perform(request(secondToken,4)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value(PixelWriteBusyException.MESSAGE));
                var closing=callers.submit(executor::close);
                first.get(5,TimeUnit.SECONDS).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("Pixel write outcome is unknown."))
                        .andExpect(header().doesNotExist("Retry-After")).andExpect(jsonPath("$.remainingMillis").doesNotExist());
                assertFalse(closing.isDone());verify(c.getBean(PixelCooldown.class),never()).startCooldown(anyLong());verifyNoInteractions(c.getBean(PixelBroadcastService.class));
                release.countDown();closing.get(5,TimeUnit.SECONDS);verifyNoInteractions(c.getBean(InMemoryTileBoard.class));
            }finally{release.countDown();}}
        });
    }
    @Test void dirty500HasNoPostprocessingAndFatal500ThenNotReady503RemainDistinct(){
        context().run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();var token=c.getBean(ServiceJwtTokens.class).issueAccess(42).getTokenValue();
            var dirty=c.getBean(DirtyTileTracker.class);var ready=c.getBean(ServiceReadiness.class);
            doThrow(new IllegalStateException("dirty")).when(dirty).markDirty(any(),anyLong(),anyLong());
            assertThrows(ServletException.class,()->mvc.perform(request(token,3)));assertTrue(ready.isReady());
            verify(c.getBean(PixelCooldown.class),never()).startCooldown(anyLong());verifyNoInteractions(c.getBean(PixelBroadcastService.class));
            doThrow(new IllegalStateException("wal")).when(c.getBean(WalAppender.class)).appendBatchAndFsync(any());
            assertThrows(ServletException.class,()->mvc.perform(request(token,4)));assertFalse(ready.isReady());
            mvc.perform(get("/error").with(r->{r.setDispatcherType(DispatcherType.ERROR);r.setAttribute(RequestDispatcher.ERROR_STATUS_CODE,500);return r;})).andExpect(status().isInternalServerError());
            mvc.perform(request(token,5)).andExpect(status().isServiceUnavailable());
        });
    }
}
