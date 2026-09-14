package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.*;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** default/stub 모두 생성·종료 무 I/O. 파일 종료 annotation owner와 공유 identity 검증 */
class SegmentedWalProfileTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings={"default","stub"})
    void profilesUseOneReplaySourceAndOneStorageDestroyOwnerWithoutIo(String profile) throws Exception {
        Path base=directory.resolve("not-created/wal");
        var storage=spy(storage(base,1));
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profile);
            context.registerBean(SegmentedWalStorage.class,()->storage);
            context.register(FileWalAppender.class,FileWalReplaySource.class,StubWalReplaySource.class);
            context.refresh();
            assertEquals(1,context.getBeansOfType(WalReplaySource.class).size());
            assertSame(storage,ReflectionTestUtils.getField(context.getBean(FileWalAppender.class),"storage"));
            if(profile.equals("stub")) assertInstanceOf(StubWalReplaySource.class,context.getBean(WalReplaySource.class));
            else assertSame(storage,ReflectionTestUtils.getField(context.getBean(FileWalReplaySource.class),"storage"));
            assertEquals(0,Arrays.stream(FileWalAppender.class.getDeclaredMethods()).filter(method->method.isAnnotationPresent(PreDestroy.class)).count());
            assertEquals(1,Arrays.stream(SegmentedWalStorage.class.getDeclaredMethods()).filter(method->method.isAnnotationPresent(PreDestroy.class)).count());
            assertFalse(Files.exists(base.getParent()));
        }
        verify(storage,times(1)).close();verify(storage,never()).openDirectory(any());verify(storage,never()).readAttributes(any());
        verify(storage,never()).openReader(any());verify(storage,never()).createDirectories(any());
        verify(storage,never()).openFileChannel(any(),any(java.nio.file.StandardOpenOption[].class));
        assertFalse(Files.exists(base.getParent()));
        assertThrows(IllegalStateException.class,()->storage.readAfter(0));
    }
}
