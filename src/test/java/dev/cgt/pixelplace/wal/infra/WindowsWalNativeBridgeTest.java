package dev.cgt.pixelplace.wal.infra;

import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** native 성공/실패 반환과 handle 소유권 검증. 실제 Windows 성공 증거는 별도 N17 task 담당 */
class WindowsWalNativeBridgeTest {
    private final WindowsWalNativeBridge.Kernel32 api = mock(WindowsWalNativeBridge.Kernel32.class);
    private final Pointer handle = Pointer.createConstant(123);
    private final Path file = Path.of("C:/fixture/한글 wal");
    private final AtomicInteger error = new AtomicInteger(5);
    private final WindowsWalNativeBridge bridge = new WindowsWalNativeBridge(() -> api, error::get);

    private void successfulHandle() {
        when(api.CreateFileW(any(), anyInt(), eq(7), isNull(), eq(3), anyInt(), isNull())).thenReturn(handle);
        when(api.FlushFileBuffers(handle)).thenReturn(1);
        when(api.CloseHandle(handle)).thenReturn(1);
        when(api.GetFileInformationByHandleEx(eq(handle), eq(1), any(), eq(24))).thenAnswer(call -> {
            Pointer buffer = call.getArgument(2);
            buffer.setInt(16, 1); buffer.setByte(20, (byte)0); buffer.setByte(21, (byte)0);
            return 1;
        });
    }

    @Test void constructionDoesNotLoadNativeLibrary() {
        AtomicInteger loads = new AtomicInteger();
        new WindowsWalNativeBridge(() -> { loads.incrementAndGet(); throw new UnsatisfiedLinkError(); }, () -> 0);
        assertEquals(0, loads.get());
    }

    @Test void unsupportedOsRejectsBeforeLoadingWithoutFallback() {
        String original=System.getProperty("os.name");
        try {
            System.setProperty("os.name","unsupported-fixture");
            var unsupported=new WindowsWalNativeBridge();
            assertThrows(UnsupportedOperationException.class,()->unsupported.syncDirectory(file));
        } finally {System.setProperty("os.name",original);}
    }

    @ParameterizedTest @ValueSource(strings={"network","not-ntfs"})
    void unsupportedVolumeCannotProceedToDirectoryFlush(String point) {
        when(api.GetVolumePathNameW(any(),any(),anyInt())).thenAnswer(c->{char[] out=c.getArgument(1);"C:\\".getChars(0,3,out,0);return 1;});
        when(api.GetDriveTypeW(any())).thenReturn(point.equals("network")?4:3);
        when(api.GetVolumeNameForVolumeMountPointW(any(),any(),anyInt())).thenReturn(1);
        when(api.GetVolumeInformationW(any(),any(),anyInt(),any(),any(),any(),any(),anyInt())).thenAnswer(c->{char[] fs=c.getArgument(6);"FAT32".getChars(0,5,fs,0);return 1;});
        assertThrows(IOException.class,()->bridge.volume(file));verify(api,never()).FlushFileBuffers(any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"directory,null", "directory,zero", "directory,invalid",
            "recovery,null", "recovery,zero", "recovery,invalid"})
    void invalidOpenNeverFlushesOrCloses(String operation, String value) {
        successfulHandle();
        when(api.CreateFileW(any(), anyInt(), anyInt(), isNull(), anyInt(), anyInt(), isNull()))
                .thenReturn(value.equals("null") ? null : Pointer.createConstant(value.equals("zero") ? 0 : -1L));
        IOException failure = assertThrows(IOException.class, () -> {
            if (operation.equals("directory")) bridge.syncDirectory(file); else bridge.syncRecoveredFile(file);
        });
        assertTrue(failure.getMessage().contains("win32=5"));
        verify(api, never()).GetFileInformationByHandleEx(any(), anyInt(), any(), anyInt());
        verify(api, never()).FlushFileBuffers(any()); verify(api, never()).CloseHandle(any());
    }

    @ParameterizedTest @ValueSource(strings={"information","close"})
    void queryRequiredInformationOrCloseFailureCannotReturnPresent(String point) {
        successfulHandle();
        if (point.equals("information")) when(api.GetFileInformationByHandleEx(any(), anyInt(), any(), anyInt())).thenReturn(0);
        else when(api.CloseHandle(handle)).thenReturn(0);
        IOException failure = assertThrows(IOException.class, () -> bridge.probe(file));
        assertTrue(failure.getMessage().contains("win32=5"));
        verify(api).CloseHandle(handle);
        verify(api, never()).FlushFileBuffers(any());
    }

    @ParameterizedTest @ValueSource(strings={"directory","recovery"})
    void successfulSyncUsesCorrectFlagsAndClosesBeforeReturn(String operation) throws Exception {
        successfulHandle();
        if (operation.equals("directory")) bridge.syncDirectory(file); else bridge.syncRecoveredFile(file);
        var order = inOrder(api);
        order.verify(api).CreateFileW(argThat(p -> p.toString().equals(file.toString())), eq(0xc0000000), eq(7),
                isNull(), eq(3), eq(operation.equals("directory") ? 0x02000000 : 0), isNull());
        if (operation.equals("recovery")) order.verify(api).GetFileInformationByHandleEx(eq(handle), eq(1), any(), eq(24));
        order.verify(api).FlushFileBuffers(handle); order.verify(api).CloseHandle(handle);
    }

    @ParameterizedTest @ValueSource(strings={"information","flush","close"})
    void everyRequiredRecoveryFailureRejectsAndClosesValidHandle(String point) {
        successfulHandle();
        if (point.equals("information")) when(api.GetFileInformationByHandleEx(any(), anyInt(), any(), anyInt())).thenReturn(0);
        if (point.equals("flush")) when(api.FlushFileBuffers(handle)).thenReturn(0);
        if (point.equals("close")) when(api.CloseHandle(handle)).thenReturn(0);
        assertThrows(IOException.class, () -> bridge.syncRecoveredFile(file));
        verify(api).CloseHandle(handle);
        if (point.equals("information")) verify(api, never()).FlushFileBuffers(any());
    }

    @ParameterizedTest @ValueSource(strings={"zero-links","pending","directory","unsigned"})
    void standardInfoUsesUnsignedDwordAndByteBooleans(String state) throws Exception {
        successfulHandle();
        when(api.GetFileInformationByHandleEx(eq(handle), eq(1), any(), eq(24))).thenAnswer(call -> {
            Pointer buffer=call.getArgument(2);
            buffer.setInt(16, state.equals("zero-links") ? 0 : -1);
            buffer.setByte(20, (byte)(state.equals("pending") ? 1 : 0));
            buffer.setByte(21, (byte)(state.equals("directory") ? 1 : 0));
            return 1;
        });
        if (state.equals("unsigned")) bridge.syncRecoveredFile(file);
        else { assertThrows(IOException.class, () -> bridge.syncRecoveredFile(file)); verify(api, never()).FlushFileBuffers(any()); }
        verify(api).CloseHandle(handle);
    }

    @Test void lastErrorIsSavedBeforeCleanupChangesIt() {
        successfulHandle();
        when(api.FlushFileBuffers(handle)).thenReturn(0);
        when(api.CloseHandle(handle)).thenAnswer(call -> { error.set(32); return 0; });
        IOException failure = assertThrows(IOException.class, () -> bridge.syncDirectory(file));
        assertTrue(failure.getMessage().contains("win32=5"));
        assertTrue(failure.getSuppressed()[0].getMessage().contains("win32=32"));
    }

    @ParameterizedTest @ValueSource(strings={"runtime/error","error/runtime","error/error","same"})
    void cleanupPreservesErrorPriorityAndIdentity(String kind) {
        successfulHandle();
        Throwable first = kind.startsWith("runtime") ? new IllegalStateException("first") : new AssertionError("first");
        Throwable later = kind.equals("same") ? first : kind.endsWith("runtime") ? new IllegalStateException("close") : new AssertionError("close");
        when(api.FlushFileBuffers(handle)).thenThrow(first); when(api.CloseHandle(handle)).thenThrow(later);
        Throwable actual = assertThrows(Throwable.class, () -> bridge.syncDirectory(file));
        Throwable primary = first instanceof Error ? first : later;
        assertSame(primary, actual);
        assertEquals(kind.equals("same") ? 0 : 1, actual.getSuppressed().length);
        verify(api).CloseHandle(handle);
    }

    @ParameterizedTest @ValueSource(ints={2,3,5,32})
    void queryOpenPreservesDistinctNativeErrorsWithoutClosingInvalidHandle(int code) throws Exception {
        error.set(code);
        assertEquals(code, bridge.probe(file).error());
        verify(api, never()).CloseHandle(any());
    }
}
