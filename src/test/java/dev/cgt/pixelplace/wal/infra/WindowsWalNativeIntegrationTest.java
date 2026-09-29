package dev.cgt.pixelplace.wal.infra;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** N17: 실제 production bridge/경로/Q/R 조합. 명시된 새 C: fixture만 소유하며 환경 부재는 실패 */
@Tag("windows-wal-integration")
class WindowsWalNativeIntegrationTest {
    static Path root() throws Exception {
        assertTrue(System.getProperty("os.name").startsWith("Windows"));
        assertEquals(21, Runtime.version().feature());
        String input = System.getProperty("pixelplace.test.wal-native-root");
        assertNotNull(input, "explicit native root required");
        assertTrue(input.matches("^[A-Za-z]:[\\\\/].*"));
        Path root = Path.of(input).toAbsolutePath().normalize();
        var plan=new java.util.Properties();
        try(var in=Files.newInputStream(Path.of(System.getProperty("pixelplace.test.wal-native-plan")))){plan.load(in);}
        Path expected = Path.of(plan.getProperty("fixtureRoot"));
        assertEquals(expected, root, "G-B03 fixture root mismatch");
        assertEquals(System.getProperty("user.name"),plan.getProperty("user"));
        assertEquals(System.getProperty("java.home"),Path.of(plan.getProperty("javaHome")).toString());
        assertEquals("confirmed",plan.getProperty("support"));
        assertEquals("false",plan.getProperty("elevated"));
        assertEquals("128",plan.getProperty("recordsPerCase"));assertEquals("3",plan.getProperty("missingLevels"));
        assertEquals("16777216",plan.getProperty("payloadLimit"));
        Path runtime = Path.of(plan.getProperty("runtimeWalParent")).toRealPath();
        assertFalse(root.startsWith(runtime)); assertFalse(runtime.startsWith(root));
        if (!Files.exists(root)) Files.createDirectory(root);
        assertEquals(root, root.toRealPath());
        assertEquals(Files.getFileStore(runtime), Files.getFileStore(root));
        assertEquals("NTFS", Files.getFileStore(root).type());
        assertEquals(Files.getFileAttributeView(runtime,java.nio.file.attribute.AclFileAttributeView.class).getAcl(),
                Files.getFileAttributeView(root,java.nio.file.attribute.AclFileAttributeView.class).getAcl());
        new WindowsWalFileDurability().prepareDirectory(root);
        try(var files=Files.walk(root)) {
            long total=0;for(var file:files.filter(Files::isRegularFile).toList())total+=Files.size(file);
            assertTrue(total<=Long.parseLong(plan.getProperty("payloadLimit")),"Native payload budget exceeded");
        }
        return root;
    }

    @Test void n1701RealUnicodeDirectoryRecoveryFilesAndNameQueries() throws Exception {
        Path parent = root().resolve("한글 공백-" + UUID.randomUUID());
        Files.createDirectory(parent);
        try(var boundary=new WindowsWalNativeTestBoundary()) {
        WindowsWalFileDurability durability = new WindowsWalFileDurability(boundary.bridge);
        var proof = durability.prepareDirectory(parent);
        Path file = parent.resolve("기존 파일.wal");
        byte[] content = "small native fixture\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, content);
        assertEquals(WalFileDurability.NameState.PRESENT, durability.queryName(file, proof).state());
        durability.syncRecoveredFile(file);
        durability.syncDirectory(proof);
        assertArrayEquals(content, Files.readAllBytes(file));
        Path empty = parent.resolve("빈 파일.wal");
        Files.createFile(empty);
        durability.syncRecoveredFile(empty);
        assertEquals(0, Files.size(empty));
        Path absent = parent.resolve("absent.wal");
        assertEquals(WalFileDurability.NameState.ABSENT, durability.queryName(absent, proof).state());
        assertFalse(Files.exists(absent));
        System.out.println("N17-01 production S/R/Q success; file content unchanged; jdk=" + Runtime.version()
                + "; provider=" + parent.getFileSystem().provider().getClass().getName()
                + "; jna=" + com.sun.jna.Native.VERSION + "; temp=" + System.getProperty("java.io.tmpdir"));
        var nativeVersion=com.sun.jna.Native.class.getDeclaredMethod("getNativeVersion");nativeVersion.setAccessible(true);
        assertEquals("7.0.2",nativeVersion.invoke(null));System.out.println("JNA_ACTUAL_NATIVE=7.0.2 jna.tmpdir="+System.getProperty("jna.tmpdir"));
        }
    }

    @Test void n1702MissingNestedParentsCreateRotationAndFreshAdoptionUseProductionDurability() throws Exception {
        Path base=root().resolve("nested-"+UUID.randomUUID()).resolve("two/three/wal");
        var durability=new WindowsWalFileDurability();
        try(var storage=new SegmentedWalStorage(WalStorageTestSupport.properties(base,1),WalStorageTestSupport.PARSER,
                WalStorageTestSupport.CODEC,dev.cgt.pixelplace.measurement.Measurements.disabled(),durability)) {
            storage.appendBatchAndFsync(java.util.List.of(WalStorageTestSupport.record(2),WalStorageTestSupport.record(5),WalStorageTestSupport.record(9)));
            assertEquals(3,storage.inspectFiles().size());assertEquals(9,storage.readAfter(0).walLastEventSeq());
        }
        byte[] before=Files.readAllBytes(base);
        try(var fresh=new SegmentedWalStorage(WalStorageTestSupport.properties(base,1),WalStorageTestSupport.PARSER,
                WalStorageTestSupport.CODEC,dev.cgt.pixelplace.measurement.Measurements.disabled(),new WindowsWalFileDurability())) {
            var batch=fresh.readAfter(0);fresh.prepareForRecovery(batch);
            assertEquals(3,batch.records().size());assertArrayEquals(before,Files.readAllBytes(base));
            fresh.appendAndFsync(WalStorageTestSupport.record(12));assertEquals(4,fresh.inspectFiles().size());
        }
        System.out.println("N17-02 production nested preparation/create/rotation/recovery/adoption complete; records=4");
    }

    @Test void n1704FailedWriterExitPrecedesSeparateNativeRecoveryAndLoadingFailureIsExplicit()throws Exception {
        Path parent=root().resolve("restart-"+UUID.randomUUID());Files.createDirectory(parent);Path wal=parent.resolve("wal");
        long writerPid;
        try(var writer=new WalTestJvm(WindowsWalNativeChild.class,"failed-writer",wal.toString())) {
            writerPid=writer.process.pid();writer.matching("FAILED_WRITER_COMPLETE ");writer.expectExit(0);
        }
        var before=WalRecoveryCaseVerifier.hashes(wal);assertEquals(2,before.size());
        try(var reader=new WalTestJvm(WalRecoveryJvmProbe.class,"--case",wal.toString(),"0","2,5","none","native")) {
            assertNotEquals(writerPid,reader.process.pid());Files.writeString(reader.evidence.resolve("input-hashes.txt"),before.toString());
            reader.matching("RECOVERY_CASE_EXIT ");reader.expectExit(0);
        }
        assertEquals(before,WalRecoveryCaseVerifier.hashes(wal));
        try(var negative=new WalTestJvm(WindowsWalNativeChild.class,"loading-failure",parent.resolve("missing/wal").toString())) {
            negative.matching("EXPECTED_LOADING_FAILURE ");negative.expectExit(7);
        }
    }

    @Test void n1702SuccessfulSyncThenInjectedFailurePreservesPartialParentsForFreshNativeJvm()throws Exception {
        Path parent=root().resolve("partial-"+UUID.randomUUID());Files.createDirectory(parent);
        Path first=parent.resolve("one"), wal=first.resolve("two/three/wal");
        WindowsWalNativeBridge bridge=new WindowsWalNativeBridge() {
            @Override public void syncDirectory(Path path)throws java.io.IOException {
                super.syncDirectory(path);
                if(path.equals(first))throw new java.io.IOException("Injected after actual S");
            }
        };
        try(var storage=new SegmentedWalStorage(WalStorageTestSupport.properties(wal,1),WalStorageTestSupport.PARSER,
                WalStorageTestSupport.CODEC,dev.cgt.pixelplace.measurement.Measurements.disabled(),new WindowsWalFileDurability(bridge))) {
            assertThrows(IllegalStateException.class,()->storage.appendAndFsync(WalStorageTestSupport.record(2)));
        }
        assertTrue(Files.isDirectory(first));assertFalse(Files.exists(first.resolve("two")));
        try(var fresh=new WalTestJvm(WalRecoveryJvmProbe.class,"--case",wal.toString(),"0","empty","none","native")) {
            fresh.matching("RECOVERY_CASE_EXIT ");fresh.expectExit(0);
        }
        assertTrue(Files.isDirectory(wal.getParent()));assertFalse(Files.exists(wal));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"control","read-share3","read-share7","metadata-share0"})
    void n1703ExternalHolderUsesActualDeleteAndProductionNameSyncJudgment(String mode) throws Exception {
        Path parent=root().resolve("holder-"+UUID.randomUUID());Files.createDirectory(parent);Path base=parent.resolve("wal");
        try(var boundary=new WindowsWalNativeTestBoundary();
            var storage=new SegmentedWalStorage(WalStorageTestSupport.properties(base,1),WalStorageTestSupport.PARSER,
                    WalStorageTestSupport.CODEC,dev.cgt.pixelplace.measurement.Measurements.disabled(),new WindowsWalFileDurability(boundary.bridge))) {
            storage.appendBatchAndFsync(java.util.List.of(WalStorageTestSupport.record(2),WalStorageTestSupport.record(5),WalStorageTestSupport.record(9)));
            WalTestJvm holder=null;
            try {
                if(!mode.equals("control")) {
                    holder=new WalTestJvm(WindowsWalNativeChild.class,"holder",base.toString(),mode.startsWith("metadata")?"128":"2147483648",
                            mode.endsWith("share3")?"3":mode.endsWith("share7")?"7":"0");
                    assertTrue(holder.matching("OPEN ").contains("pid="));
                }
                var result=storage.deleteCommittedPrefix(5);
                boolean blocked=mode.equals("read-share3");
                assertEquals(blocked?dev.cgt.pixelplace.wal.application.WalRetentionResult.delayed(0,0,
                        dev.cgt.pixelplace.wal.application.WalRetentionResult.DelayKind.IO)
                        :dev.cgt.pixelplace.wal.application.WalRetentionResult.completed(2),result);
                assertEquals(blocked,Files.exists(base));
                if(holder!=null) {
                    holder.send("observe");
                    assertEquals(blocked?"STATE links=1 pending=false directory=false":"STATE links=0 pending=true directory=false",holder.matching("STATE "));
                    holder.send("close");assertEquals("CLOSED handles=1/1",holder.matching("CLOSED "));holder.expectExit(0);
                }
                if(blocked)assertEquals(dev.cgt.pixelplace.wal.application.WalRetentionResult.completed(2),storage.deleteCommittedPrefix(5));
                assertEquals(9,storage.readAfter(5).walLastEventSeq());
                System.out.println("N17-03 mode="+mode+" production deletion judgment verified");
            } finally {if(holder!=null)holder.close();}
        }
    }
}
