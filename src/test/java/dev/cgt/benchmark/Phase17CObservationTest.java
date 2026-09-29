package dev.cgt.benchmark;

import dev.cgt.pixelplace.flush.application.*;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.tile.application.*;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.application.*;
import dev.cgt.pixelplace.wal.infra.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 관측의 DI/수명·실패 instance 보존. 외부 DB/native 성공을 대체하는 테스트 아님 */
class Phase17CObservationTest {
    @TempDir Path temporary;
    @Test void actualNewlineScansKeepReaderClosesSeparateFromWriterRotationAndAdoption() throws Exception {
        var properties=new WalProperties();properties.setActiveFile(temporary.resolve("pixel-place.wal"));properties.setMaxSegmentBytes(1);
        var trace=new Phase17CTrace(1000,1000000);
        var measurement=new PixelMeasurement(true,new SimpleMeterRegistry());
        var parser=new WalRecordParser(BenchmarkJson.MAPPER);var codec=new WalRecordJsonCodec(BenchmarkJson.MAPPER);
        // native 경계만 테스트 대역. 실제 append/readAfter의 newline FileChannel과 writer close 분류 검증
        try(var storage=new Phase17CObservedStorage(properties,parser,codec,measurement,new TestWalFileDurability(),trace)) {
            storage.appendAndFsync(Phase17CFixtures.records(List.of(1L,2L)).getFirst());
            storage.readAfter(0);storage.readAfter(0);
            assertEquals(0,trace.count("file.adopt","return"));
            assertEquals(0,trace.count("file.close","return"));
            assertEquals(2,trace.count("file.read","return"));
            assertEquals(2,trace.count("file.read.close","return"));
            storage.appendAndFsync(Phase17CFixtures.records(List.of(1L,2L)).getLast());
            storage.readAfter(0);
            assertEquals(1,trace.count("file.close","return"));
        }
        assertEquals(2,trace.count("file.create","return"));
        assertEquals(2,trace.count("file.close","return"));
        assertEquals(trace.count("file.read","return"),trace.count("file.read.close","return"));
        try(var storage=new Phase17CObservedStorage(properties,parser,codec,measurement,new TestWalFileDurability(),trace)) {
            storage.appendAndFsync(new dev.cgt.pixelplace.wal.domain.WalRecord(8,1,0,0,0,1,0,17,java.time.LocalDateTime.of(2026,9,28,0,0)));
        }
        assertEquals(1,trace.count("file.adopt","return"));
        assertEquals(1,trace.count("adoption_force","return"));
        assertEquals(4,trace.count("file.close","return"));
    }
    @Test void decoratedConcreteBeansShareOneStorageAndSpringStillOwnsItsCloseWithoutNativeLoading() throws Exception {
        Path wal=temporary.resolve("absent/pixel-place.wal"); var trace=new Phase17CTrace(1000,1000000);
        var observation=new Phase17CObservation(trace,wal); var context=new AnnotationConfigApplicationContext();
        context.registerBean(PixelMeasurement.class,()->new PixelMeasurement(true,new SimpleMeterRegistry()));
        context.registerBean(WalProperties.class,()->{var p=new WalProperties();p.setActiveFile(wal);return p;});
        context.registerBean(tools.jackson.databind.ObjectMapper.class,()->BenchmarkJson.MAPPER);
        context.register(WindowsWalFileDurability.class,SegmentedWalStorage.class,WalRecordParser.class,WalRecordJsonCodec.class,FileWalAppender.class,FileWalReplaySource.class,FileWalStoragePreparation.class);
        observation.install(context);context.refresh();
        Object storage=context.getBean(SegmentedWalStorage.class);
        assertSame(storage,context.getBean(WalSegmentRetention.class));
        assertDoesNotThrow(()->Phase17CVerificationMain.requireSameField(storage,"measurement",context.getBean(PixelMeasurement.class)));
        assertThrows(IllegalStateException.class,()->Phase17CVerificationMain.requireSameField(storage,"measurement",new PixelMeasurement(false,new SimpleMeterRegistry())));
        for(String name:List.of("fileWalAppender","fileWalReplaySource","fileWalStoragePreparation")) {
            Object bean=observation.original(name);if(bean==null)bean=context.getBean(name);
            var field=org.springframework.aop.support.AopUtils.getTargetClass(bean).getDeclaredField("storage");field.setAccessible(true);assertSame(storage,field.get(bean));
        }
        var durability=context.getBean(WindowsWalFileDurability.class);assertInstanceOf(Phase17CWindowsObservation.class,durability);
        var bridgeField=WindowsWalFileDurability.class.getDeclaredField("bridge");bridgeField.setAccessible(true);
        var loaded=WindowsWalNativeBridge.class.getDeclaredField("loaded");loaded.setAccessible(true);assertNull(loaded.get(bridgeField.get(durability)));
        Object original=observation.original("segmentedWalStorage");context.close();
        var closed=SegmentedWalStorage.class.getDeclaredField("closed");closed.setAccessible(true);assertEquals(true,closed.get(original));
        assertFalse(Files.exists(wal.getParent()));assertTrue(trace.events().isEmpty());
    }
    @Test void dirtyObservationDelegatesOnceWithoutSyntheticDrainOrRestoreAndOverflowDoesNotChangeResult() {
        var actual=new SynchronizedDirtyTileTracker();var trace=new Phase17CTrace(1,64);var observation=new Phase17CObservation(trace,temporary.resolve("wal"));
        var tracker=(DirtyTileTracker)observation.postProcessAfterInitialization(actual,"dirty");
        tracker.markDirty(new TileKey(0,0,0),2,1);
        var drained=tracker.drainDirtyTiles();assertEquals(1,drained.size());assertTrue(actual.drainDirtyTiles().isEmpty());assertFalse(trace.complete());
    }
    @Test void existingAdviceAndOriginalResultExceptionAndErrorIdentityArePreserved() {
        var worker=mock(FlushWorker.class);var called=new AtomicInteger();var advice=new AtomicInteger();
        var prior=new ProxyFactory(worker);prior.setProxyTargetClass(true);prior.addAdvice((MethodInterceptor)invocation->{advice.incrementAndGet();return invocation.proceed();});
        var trace=new Phase17CTrace(1000,1000000);var observation=new Phase17CObservation(trace,temporary.resolve("wal"));
        var observed=(FlushWorker)observation.postProcessAfterInitialization(prior.getProxy(),"flushWorker");
        when(worker.flushOnce()).thenAnswer(invocation->{called.incrementAndGet();return FlushRunResult.COMMITTED;});
        assertSame(FlushRunResult.COMMITTED,observed.flushOnce());assertEquals(1,called.get());assertEquals(1,advice.get());
        var failure=new IllegalStateException("fixture");when(worker.flushOnce()).thenThrow(failure);assertSame(failure,assertThrows(IllegalStateException.class,observed::flushOnce));
        reset(worker);var error=new AssertionError("fixture");var suppressed=new IllegalStateException("secondary");error.addSuppressed(suppressed);when(worker.flushOnce()).thenThrow(error);
        assertSame(error,assertThrows(AssertionError.class,observed::flushOnce));assertArrayEquals(new Throwable[]{suppressed},error.getSuppressed());assertEquals(3,advice.get());
    }
}
