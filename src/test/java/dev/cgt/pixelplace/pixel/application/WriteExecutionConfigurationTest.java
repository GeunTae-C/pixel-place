package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.measurement.Measurements;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.wal.application.*;
import dev.cgt.pixelplace.wal.infra.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;

/** 실제 DI 의존관계의 종료 순서와 I/O 없는 설정 검증 */
class WriteExecutionConfigurationTest {
    @TempDir Path directory;
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(WriteExecutionConfiguration.class)
                .withBean(FlushBoundaryCoordinator.class,()->new FlushBoundaryCoordinator(Measurements.disabled()))
                .withBean(PixelWriteService.class,()->mock(PixelWriteService.class))
                .withBean(DirtyTileTracker.class,()->mock(DirtyTileTracker.class))
                .withBean(ServiceReadiness.class,ServiceReadiness::new)
                .withBean(dev.cgt.pixelplace.measurement.PixelMeasurement.class,Measurements::disabled);
    }
    // 실제 main/stub YAML과 표준 환경변수 사용. 비밀 import와 외부 서비스만 격리
    private ApplicationContextRunner configData(String profile, String environmentMode, String explicitMode) {
        return new ApplicationContextRunner().withInitializer(c -> {
                    c.getEnvironment().getPropertySources().replace("systemEnvironment",
                            new SystemEnvironmentPropertySource("systemEnvironment", environmentMode.equals("-")
                                    ? Map.of() : Map.of("PIXELPLACE_WRITE_MODE", environmentMode)));
                    new ConfigDataApplicationContextInitializer().initialize(c);
                }).withPropertyValues("spring.config.location=classpath:/application.yml", "spring.config.import=",
                        "spring.profiles.active="+profile, explicitMode.equals("-")
                                ? "test.no-mode-override=true" : "pixel-place.write.mode="+explicitMode)
                .withUserConfiguration(WriteExecutionConfiguration.class);
    }

    @ParameterizedTest
    @CsvSource({"default,-,-,group", "default,-,single,single", "default,-,group,group",
            "stub,-,-,single", "stub,-,single,single", "default,single,-,single",
            "default,group,-,group", "stub,single,-,single", "default,single,group,group",
            "default,group,single,single", "stub,group,single,single"})
    void configDataAndEnvironmentSelectOneExecutorAndCloseItBeforeStorageWithoutIo(
            String profile, String environmentMode, String explicitMode, String expected) {
        Path path=directory.resolve("absent/wal");
        var properties=new WalProperties();properties.setActiveFile(path);
        var mapper=JsonMapper.builder().build();
        var storage=spy(new SegmentedWalStorage(properties,new WalRecordParser(mapper),new WalRecordJsonCodec(mapper),Measurements.disabled()));
        var executor=new java.util.concurrent.atomic.AtomicReference<PixelWriteExecutor>();
        doAnswer(call->{assertTrue(executor.get().snapshot().closed());return call.callRealMethod();}).when(storage).close();
        configData(profile,environmentMode,explicitMode)
                .withBean(SegmentedWalStorage.class,()->storage)
                .withBean(dev.cgt.pixelplace.measurement.PixelMeasurement.class,Measurements::disabled)
                .withBean(DirtyTileTracker.class,()->mock(DirtyTileTracker.class))
                .withBean(InMemoryTileBoard.class,()->mock(InMemoryTileBoard.class))
                .withUserConfiguration(FileWalAppender.class,PixelWriteService.class,EventSeqManager.class,ServiceReadiness.class,FlushBoundaryCoordinator.class)
                .run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(PixelWriteExecutor.class).size());
                    executor.set(c.getBean(PixelWriteExecutor.class));
                    assertEquals("33554432",c.getEnvironment().getProperty("pixel-place.wal.max-segment-bytes"));
                    assertEquals(expected,c.getBean(WriteExecutionProperties.class).getMode());
                    if(expected.equals("group"))assertInstanceOf(GroupPixelWriteExecutor.class,executor.get());
                    else assertInstanceOf(SinglePixelWriteExecutor.class,executor.get());
                    assertFalse(executor.get().snapshot().workerAlive());assertEquals(0,executor.get().snapshot().workerInFlight());
                    assertFalse(Files.exists(path.getParent()));});
        verify(storage,times(1)).close();assertFalse(Files.exists(path.getParent()));
    }
    @ParameterizedTest
    @ValueSource(strings={"mode=invalid","group.max-batch-size=0","group.max-batch-size=129",
            "group.max-outstanding=0","group.max-outstanding=4097","group.max-outstanding=15",
            "group.queue-timeout=0s","group.queue-timeout=-1s","group.queue-timeout=30.001s",
            "group.shutdown-grace=0s","group.shutdown-grace=-1s","group.shutdown-grace=60.001s",
            "group.max-bach-size=16","unexpected=true"})
    void invalidStrategiesRejectStartupEvenInSingleMode(String property) {
        context().withPropertyValues("pixel-place.write.mode=single","pixel-place.write."+property)
                .run(c->assertNotNull(c.getStartupFailure()));
    }

    @ParameterizedTest @CsvSource({"-,group","group,-","single,group"})
    void explicitStubGroupRejectsStartupThroughConfigData(String environmentMode,String explicitMode) {
        configData("stub",environmentMode,explicitMode)
                .withBean(FlushBoundaryCoordinator.class,()->new FlushBoundaryCoordinator(Measurements.disabled()))
                .withBean(PixelWriteService.class,()->mock(PixelWriteService.class))
                .withBean(DirtyTileTracker.class,()->mock(DirtyTileTracker.class))
                .withBean(ServiceReadiness.class,ServiceReadiness::new)
                .withBean(dev.cgt.pixelplace.measurement.PixelMeasurement.class,Measurements::disabled)
                .run(c->assertNotNull(c.getStartupFailure()));
    }
    @ParameterizedTest @ValueSource(ints={1,128})
    void validInclusiveBoundsCreateSingle(int size) {
        context().withPropertyValues("pixel-place.write.mode=single","pixel-place.write.group.max-batch-size="+size,"pixel-place.write.group.max-outstanding=4096",
                "pixel-place.write.group.queue-timeout=30s","pixel-place.write.group.shutdown-grace=60s")
                .run(c->assertNull(c.getStartupFailure()));
    }

    @org.junit.jupiter.api.Test void minimumCapacityAndPositiveDurationsAreAcceptedButNullDurationsAreRejected() {
        context().withPropertyValues("pixel-place.write.group.max-batch-size=1","pixel-place.write.group.max-outstanding=1",
                "pixel-place.write.group.queue-timeout=1ns","pixel-place.write.group.shutdown-grace=1ns")
                .run(c->assertNull(c.getStartupFailure()));
        var properties=new WriteExecutionProperties();
        assertEquals("group",properties.getMode());
        properties.getGroup().setQueueTimeout(null);
        assertThrows(IllegalArgumentException.class,properties::validate);
        properties.getGroup().setQueueTimeout(java.time.Duration.ofSeconds(1));
        properties.getGroup().setShutdownGrace(null);
        assertThrows(IllegalArgumentException.class,properties::validate);
    }

    @org.junit.jupiter.api.Test void actualDefaultRequestStartsNamedPlatformWorkerAfterLazyBeanCreation() {
        var wal=mock(WalAppender.class); var dirty=mock(DirtyTileTracker.class); var board=mock(InMemoryTileBoard.class);
        var worker=new java.util.concurrent.atomic.AtomicReference<Thread>();
        doAnswer(call->{worker.set(Thread.currentThread());return null;}).when(wal).appendBatchAndFsync(any());
        when(board.applyPixel(1,2,3)).thenReturn(new dev.cgt.pixelplace.tile.domain.TileMutationResult(
                new dev.cgt.pixelplace.tile.domain.TileKey(0,0,0),1));
        configData("default","-","-").withBean(WalAppender.class,()->wal)
                .withBean(DirtyTileTracker.class,()->dirty).withBean(InMemoryTileBoard.class,()->board)
                .withBean(dev.cgt.pixelplace.measurement.PixelMeasurement.class,Measurements::disabled)
                .withUserConfiguration(PixelWriteService.class,EventSeqManager.class,ServiceReadiness.class,FlushBoundaryCoordinator.class)
                .run(c->{
                    assertNull(c.getStartupFailure());var executor=c.getBean(PixelWriteExecutor.class);
                    assertInstanceOf(GroupPixelWriteExecutor.class,executor); assertFalse(executor.snapshot().workerAlive());
                    verifyNoInteractions(wal); c.getBean(ServiceReadiness.class).markReady();
                    assertEquals(1,executor.execute(7,1,2,3).eventSeq());
                    assertEquals("pixel-group-write-worker",worker.get().getName());
                    assertFalse(worker.get().isVirtual());assertFalse(worker.get().isDaemon());
                    verify(dirty).markDirty(any(),eq(1L),eq(1L));
                });
        assertFalse(worker.get().isAlive());
    }

    @ParameterizedTest @ValueSource(strings={"single","group"})
    void springCloseWaitsForRegisteredMemoryAndDirtyBeforeClosingActualStorage(String mode) throws Exception {
        var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("mode",java.util.Map.of("pixel-place.write.mode",mode)));
        var properties=new WalProperties();properties.setActiveFile(directory.resolve("runtime/wal"));
        var mapper=JsonMapper.builder().build();
        var storage=spy(new SegmentedWalStorage(properties,new WalRecordParser(mapper),new WalRecordJsonCodec(mapper),Measurements.disabled()));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var board=mock(InMemoryTileBoard.class);var dirty=mock(DirtyTileTracker.class);
        when(board.applyPixel(1,2,3)).thenAnswer(call->{SinglePixelWriteExecutorTest.pause(entered,release);return new dev.cgt.pixelplace.tile.domain.TileMutationResult(new dev.cgt.pixelplace.tile.domain.TileKey(0,0,0),1);});
        context.registerBean(SegmentedWalStorage.class,()->storage);
        context.registerBean(dev.cgt.pixelplace.measurement.PixelMeasurement.class,Measurements::disabled);
        context.registerBean(InMemoryTileBoard.class,()->board);context.registerBean(DirtyTileTracker.class,()->dirty);
        context.register(WriteExecutionConfiguration.class,FileWalAppender.class,PixelWriteService.class,EventSeqManager.class,ServiceReadiness.class,FlushBoundaryCoordinator.class);
        context.refresh();context.getBean(ServiceReadiness.class).markReady();var executor=context.getBean(PixelWriteExecutor.class);
        try(var threads=Executors.newFixedThreadPool(3)) {
            try {
                var work=threads.submit(()->executor.execute(7,1,2,3));assertTrue(entered.await(5,TimeUnit.SECONDS));
                var closeStarted=new CountDownLatch(1);
                var closing=threads.submit(()->{closeStarted.countDown();context.close();});assertTrue(closeStarted.await(5,TimeUnit.SECONDS));
                if(mode.equals("single")) {
                    SinglePixelWriteExecutorTest.await(()->!executor.snapshot().accepting());
                    assertFalse(executor.snapshot().closed());assertEquals(1,executor.snapshot().activeCount());
                } else {
                    // memory가 결과 guard를 소유하므로 종료/일관된 snapshot도 해당 연산의 반환 뒤에 확정
                    var observing=threads.submit(executor::snapshot);
                    assertThrows(TimeoutException.class,()->observing.get(50,TimeUnit.MILLISECONDS));
                }
                verify(storage,never()).close();
                assertThrows(TimeoutException.class,()->closing.get(50,TimeUnit.MILLISECONDS));
                release.countDown();work.get(5,TimeUnit.SECONDS);closing.get(5,TimeUnit.SECONDS);
                var order=inOrder(dirty,storage);order.verify(dirty).markDirty(any(),eq(1L),eq(1L));order.verify(storage).close();
                assertTrue(executor.snapshot().closed());assertEquals(0,executor.snapshot().activeCount());
            }finally{release.countDown();}
        }finally{context.close();}
    }
}
