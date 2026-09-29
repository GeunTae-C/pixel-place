package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import dev.cgt.pixelplace.wal.application.WalRecordParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** canonical 파일군과 prefix 정리 후 suffix 판정. 실제 삭제 기능은 B의 검증 범위 */
class SegmentedWalFileSetTest {
    @TempDir Path directory;

    @Test
    void actualSymlinkInManagedNamespaceIsRejectedWithoutFollowingTarget() throws Exception {
        String prepared=System.getProperty("pixelplace.test.symlink-fixture");
        if(prepared!=null) {
            // 운영자 생성 입력은 읽기 전용 검증. 실제 symlink/target 내용 확인을 대역으로 대체하지 않음
            assertTrue(Path.of(prepared).isAbsolute());
            Path fixture=Path.of(prepared).toRealPath();
            Path link=fixture.resolve("wal"),target=fixture.resolve("unrelated-target");
            assertTrue(Files.isSymbolicLink(link));assertEquals(target,Files.readSymbolicLink(link));
            assertEquals("preserved",Files.readString(target));
            assertThrows(IllegalStateException.class,()->storage(link).inspectFiles());
            assertEquals("preserved",Files.readString(target));assertTrue(Files.isSymbolicLink(link));
            return;
        }
        Path target=directory.resolve("unrelated-target");
        Files.writeString(target,"preserved");
        Path link=directory.resolve("wal");
        try {
            Files.createSymbolicLink(link,target);
        } catch (FileSystemException unavailable) {
            // 실제 생성 권한과 attributes seam 판정은 별도 증거로 구분
            org.junit.jupiter.api.Assumptions.assumeTrue(false,"OS_SYMLINK_CREATE_UNAVAILABLE: "+unavailable.getClass().getSimpleName());
        }
        assertThrows(IllegalStateException.class,()->storage(link).inspectFiles());
        assertEquals("preserved",Files.readString(target));
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void absentAndEmptyParentsDoNotCreateFilesAndEmptyLegacyIsAllowed() throws Exception {
        SegmentedWalStorage absent = storage(directory.resolve("missing/wal"));
        assertTrue(absent.inspectFiles().isEmpty());
        assertFalse(Files.exists(directory.resolve("missing")));
        SegmentedWalStorage storage = storage(directory.resolve("wal"));
        assertTrue(storage.inspectFiles().isEmpty());
        Files.createFile(storage.segmentPath(0));
        assertEquals(0, storage.inspectFiles().getFirst().size());
    }

    @ParameterizedTest
    @ValueSource(ints={0,4})
    void continuousFamiliesAndEmptyLatestActiveAreAccepted(int first) throws Exception {
        SegmentedWalStorage storage = storage(directory.resolve("wal"));
        for(int i=first;i<first+2;i++) Files.writeString(storage.segmentPath(i), "nonempty\n");
        Files.createFile(storage.segmentPath(first+2));
        List<SegmentedWalStorage.SegmentFile> files = storage.inspectFiles();
        assertEquals(List.of((long)first, first+1L, first+2L), files.stream().map(SegmentedWalStorage.SegmentFile::number).toList());
        assertEquals(0L, files.getLast().size());
    }

    @ParameterizedTest
    @ValueSource(ints={0,4})
    void numberGapIsRejected(int first) throws Exception {
        SegmentedWalStorage storage = storage(directory.resolve("wal"));
        Files.writeString(storage.segmentPath(first), "preserved\n");
        Files.writeString(storage.segmentPath(first+2), "preserved\n");
        assertThrows(IllegalStateException.class, storage::inspectFiles);
        assertEquals("preserved\n", Files.readString(storage.segmentPath(first)));
    }

    @ParameterizedTest
    @ValueSource(strings={"0000000000000000000", "1", "+000000000000000001", "-000000000000000001", "00000000000000000001", "9223372036854775808", "0000000000000000001.bak", "０００００００００００００００００００１"})
    void noncanonicalManagedNameIsRejectedAndPreserved(String suffix) throws Exception {
        Path entry = directory.resolve("wal.seg-"+suffix);
        Files.writeString(entry, "preserved");
        assertThrows(IllegalStateException.class, () -> storage(directory.resolve("wal")).inspectFiles());
        assertEquals("preserved", Files.readString(entry));
    }

    @Test
    void emptyClosedAndIsolatedEmptyNumberedAreRejected() throws Exception {
        SegmentedWalStorage storage = storage(directory.resolve("wal"));
        Files.createFile(storage.segmentPath(5));
        assertThrows(IllegalStateException.class, storage::inspectFiles);
        Files.writeString(storage.segmentPath(6), "nonempty\n");
        assertThrows(IllegalStateException.class, () -> storage(directory.resolve("wal")).inspectFiles());
    }

    @Test
    void directoryAndSymlinkAttributesInNamespaceAreRejected() throws Exception {
        SegmentedWalStorage storage = storage(directory.resolve("wal"));
        Files.createDirectory(storage.segmentPath(0));
        assertThrows(IllegalStateException.class, storage::inspectFiles);
        Path link = directory.resolve("other");
        Files.createFile(link);
        SegmentedWalStorage controlled = spy(storage(link));
        BasicFileAttributes attributes = mock(BasicFileAttributes.class);
        when(attributes.isRegularFile()).thenReturn(true);
        when(attributes.isSymbolicLink()).thenReturn(true);
        doReturn(attributes).when(controlled).readAttributes(link);
        assertThrows(IllegalStateException.class, controlled::inspectFiles);
    }

    @Test
    void unrelatedEntryAttributesAreNeverReadAndEnumerationFailuresAreNotEmpty() throws Exception {
        Files.writeString(directory.resolve("wal.bak"), "preserved");
        SegmentedWalStorage storage = spy(storage(directory.resolve("wal")));
        assertTrue(storage.inspectFiles().isEmpty());
        verify(storage, never()).readAttributes(any());
        for(IOException failure : List.of(new AccessDeniedException("test"), new IOException("directory stream"))) {
            SegmentedWalStorage broken = spy(storage(directory.resolve("wal")));
            doThrow(failure).when(broken).openDirectory(directory);
            assertThrows(IllegalStateException.class, broken::inspectFiles);
            clearInvocations(broken);
            assertThrows(IllegalStateException.class, broken::inspectFiles);
            verify(broken, never()).openDirectory(any());
        }
    }

    private SegmentedWalStorage storage(Path path) {
        WalProperties properties = new WalProperties(); properties.setActiveFile(path);
        return new SegmentedWalStorage(properties, mock(WalRecordParser.class), mock(WalRecordJsonCodec.class), dev.cgt.pixelplace.measurement.Measurements.disabled(), new dev.cgt.pixelplace.wal.infra.TestWalFileDurability());
    }
}
