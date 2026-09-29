package dev.cgt.pixelplace.wal.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 작은 파일군과 native 관측 대역으로 Q 합의·안전한 부모·준비 순서의 실패 분기 검증 */
class WindowsWalFileDurabilityTest {
    @TempDir Path root;
    final WindowsWalNativeBridge bridge = mock(WindowsWalNativeBridge.class);
    WindowsWalFileDurability durability;

    private WalFileDurability.DirectoryProof setup() throws Exception {
        when(bridge.volume(any())).thenReturn("test-local-NTFS");
        when(bridge.identity(any(), anyBoolean())).thenAnswer(c -> ((Path)c.getArgument(0)).toString());
        when(bridge.attributes(any())).thenAnswer(c -> {
            Path p = c.getArgument(0);
            try {
                var a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
                return new WindowsWalNativeBridge.AttributeResult(a.isDirectory() ? 0x10 : 0x20, 0);
            } catch (NoSuchFileException absent) { return new WindowsWalNativeBridge.AttributeResult(-1, 2); }
        });
        when(bridge.probe(any())).thenAnswer(c -> Files.exists(c.getArgument(0))
                ? new WindowsWalNativeBridge.ProbeResult(new WindowsWalNativeBridge.FileStandard(1,false,false),0)
                : new WindowsWalNativeBridge.ProbeResult(null,2));
        durability = spy(new WindowsWalFileDurability(bridge));
        return durability.prepareDirectory(root);
    }

    @Test void absentRequiresAllFourObservationsAndPresentRequiresLiveStandardInfo() throws Exception {
        var proof = setup(); Path file=root.resolve("wal");
        assertEquals(WalFileDurability.NameState.ABSENT,durability.queryName(file,proof).state());
        Files.writeString(file,"preserved");
        assertEquals(WalFileDurability.NameState.PRESENT,durability.queryName(file,proof).state());
    }

    @ParameterizedTest @ValueSource(ints={3,5,32})
    void nativeErrorsOtherThanExactFileNotFoundAreNeverAbsent(int error) throws Exception {
        var proof=setup(); Path file=root.resolve("wal");
        when(bridge.attributes(file)).thenReturn(new WindowsWalNativeBridge.AttributeResult(-1,error));
        when(bridge.probe(file)).thenReturn(new WindowsWalNativeBridge.ProbeResult(null,error));
        var query=durability.queryName(file,proof);
        assertEquals(WalFileDurability.NameState.UNKNOWN,query.state());
        assertTrue(query.parentSafe()); assertNotNull(query.failure());
        assertTrue(query.failure().getMessage().contains("="+error));
    }

    @ParameterizedTest @ValueSource(strings={"listed-absent","not-listed-present","pending","zero-links","directory","probe-failure","enumeration-failure","enumeration-close"})
    void contradictoryOrFailedRequiredObservationIsUnknown(String point) throws Exception {
        var proof=setup(); Path file=root.resolve("wal");
        if (!point.equals("not-listed-present")) Files.writeString(file,"preserved");
        switch (point) {
            case "listed-absent" -> {
                when(bridge.attributes(file)).thenReturn(new WindowsWalNativeBridge.AttributeResult(-1,2));
                when(bridge.probe(file)).thenReturn(new WindowsWalNativeBridge.ProbeResult(null,2));
                doThrow(new NoSuchFileException("fixture")).when(durability).attributes(file);
            }
            case "not-listed-present" -> {
                when(bridge.attributes(file)).thenReturn(new WindowsWalNativeBridge.AttributeResult(0x20,0));
                when(bridge.probe(file)).thenReturn(new WindowsWalNativeBridge.ProbeResult(new WindowsWalNativeBridge.FileStandard(1,false,false),0));
            }
            case "pending", "zero-links", "directory" -> when(bridge.probe(file)).thenReturn(new WindowsWalNativeBridge.ProbeResult(
                    new WindowsWalNativeBridge.FileStandard(point.equals("zero-links")?0:1,point.equals("pending"),point.equals("directory")),0));
            case "probe-failure" -> when(bridge.probe(file)).thenThrow(new IOException("query close"));
            case "enumeration-failure" -> doThrow(new IOException("enumeration")).when(durability).openDirectory(root);
            case "enumeration-close" -> {
                var entries = mock(java.nio.file.DirectoryStream.class);
                when(entries.iterator()).thenReturn(List.of(file).iterator());
                doThrow(new IOException("enumeration close")).when(entries).close();
                doReturn(entries).when(durability).openDirectory(root);
            }
        }
        var result=durability.queryName(file,proof);
        assertEquals(WalFileDurability.NameState.UNKNOWN,result.state());
        assertTrue(result.parentSafe()); assertNotNull(result.failure());
    }

    @ParameterizedTest @ValueSource(strings={"volume","identity","reparse","attributes"})
    void changedOrUnverifiableParentCannotAuthorizeSync(String point) throws Exception {
        var proof=setup();
        if(point.equals("volume")) when(bridge.volume(root)).thenReturn("other-volume");
        if(point.equals("identity")) when(bridge.identity(root,true)).thenReturn("other-file-id");
        if(point.equals("reparse")) when(bridge.attributes(root)).thenReturn(new WindowsWalNativeBridge.AttributeResult(0x410,0));
        if(point.equals("attributes")) when(bridge.attributes(root)).thenReturn(new WindowsWalNativeBridge.AttributeResult(-1,5));
        clearInvocations(bridge);
        var query=durability.queryName(root.resolve("wal"),proof);
        assertEquals(WalFileDurability.NameState.UNKNOWN,query.state()); assertFalse(query.parentSafe());
        assertThrows(IOException.class,()->durability.syncDirectory(proof));
        verify(bridge,never()).syncDirectory(any());
    }

    @Test void missingParentsAreCreatedOneAtATimeBetweenTheirParentAndChildSyncs() throws Exception {
        setup();
        List<Path> synced=new ArrayList<>();
        doAnswer(c->{ Path path=c.getArgument(0); assertTrue(Files.isDirectory(path)); synced.add(path); return null; }).when(bridge).syncDirectory(any());
        Path first=root.resolve("one"), second=first.resolve("two"), third=second.resolve("three");
        durability.prepareDirectory(third);
        assertEquals(List.of(root,root,first,first,second,second,third),synced);
    }

    @Test void partialPreparationFailureLeavesCreatedDirectoryAndDoesNotCreateDescendants() throws Exception {
        setup(); Path first=root.resolve("one");
        doThrow(new IOException("child sync")).when(bridge).syncDirectory(first);
        assertThrows(IOException.class,()->durability.prepareDirectory(first.resolve("two/three")));
        assertTrue(Files.isDirectory(first)); assertFalse(Files.exists(first.resolve("two")));
    }

    @ParameterizedTest @ValueSource(strings={"anchor-S","parent-S","create","child-S"})
    void everyPreparationFailureStopsBeforeNextChildWithoutRemovingPartialState(String point)throws Exception {
        setup();Path first=root.resolve("first"),last=first.resolve("second/third");
        var attempts=new java.util.concurrent.atomic.AtomicInteger();var failure=new IOException(point);
        doAnswer(c->{
            Path path=c.getArgument(0);
            if(path.equals(root)) {
                int count=attempts.incrementAndGet();
                if(point.equals("anchor-S") || (point.equals("parent-S")&&count==2))throw failure;
            }
            if(point.equals("child-S")&&path.equals(first))throw failure;
            return null;
        }).when(bridge).syncDirectory(any());
        if(point.equals("create"))doThrow(failure).when(durability).createDirectory(first);
        assertSame(failure,assertThrows(IOException.class,()->durability.prepareDirectory(last)));
        assertEquals(point.equals("parent-S")||point.equals("child-S"),Files.exists(first));
        assertFalse(Files.exists(first.resolve("second")));
    }
}
