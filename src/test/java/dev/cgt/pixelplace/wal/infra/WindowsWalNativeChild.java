package dev.cgt.pixelplace.wal.infra;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

/** 본 검증 파일만 여는 별도 JVM holder. IPC 관측/close만 허용, delete·서비스 제어 책임 없음 */
public final class WindowsWalNativeChild {
    public static void main(String[] args) throws Exception {
        if(args[0].equals("failed-writer")) {
            Path wal=Path.of(args[1]);
            try(var boundary=new WindowsWalNativeTestBoundary();
                var storage=new SegmentedWalStorage(WalStorageTestSupport.properties(wal,1),WalStorageTestSupport.PARSER,WalStorageTestSupport.CODEC,
                        dev.cgt.pixelplace.measurement.Measurements.disabled(),new WindowsWalFileDurability(boundary.bridge)) {
                    int records;
                    @Override void forceFile(java.nio.channels.FileChannel writer,dev.cgt.pixelplace.measurement.PixelMeasurement.Operation kind)throws java.io.IOException {
                        if(kind==dev.cgt.pixelplace.measurement.PixelMeasurement.Operation.record_force && ++records==2)
                            throw new java.io.IOException("Injected complete record force");
                        super.forceFile(writer,kind);
                    }
                }) {
                storage.appendAndFsync(WalStorageTestSupport.record(2));
                try{storage.appendAndFsync(WalStorageTestSupport.record(5));throw new AssertionError("Expected force failure");}
                catch(IllegalStateException failure){if(!failure.getCause().getMessage().contains("complete record force"))throw failure;}
            }
            System.out.println("FAILED_WRITER_COMPLETE pid="+ProcessHandle.current().pid());return;
        }
        if(args[0].equals("loading-failure")) {
            var calls=new java.util.concurrent.atomic.AtomicInteger();var expected=new UnsatisfiedLinkError("Test-only native load failure");
            var bridge=new WindowsWalNativeBridge(()->{calls.incrementAndGet();throw expected;},()->0);
            var durability=new WindowsWalFileDurability(bridge);
            try(var storage=new SegmentedWalStorage(WalStorageTestSupport.properties(Path.of(args[1]),1),WalStorageTestSupport.PARSER,WalStorageTestSupport.CODEC,
                    dev.cgt.pixelplace.measurement.Measurements.disabled(),durability)) {
                if(calls.get()!=0)throw new AssertionError("Constructor loaded native");
                var batch=storage.readAfter(0);
                try{storage.prepareForRecovery(batch);throw new AssertionError("Loading failure fallback");}
                catch(UnsatisfiedLinkError failure){if(failure!=expected||calls.get()!=1)throw new AssertionError("Loading failure identity");}
            }
            System.out.println("EXPECTED_LOADING_FAILURE no_ready=true");System.exit(7);
        }
        if(!args[0].equals("holder"))throw new IllegalArgumentException("Explicit child mode required");
        var api=Native.load("kernel32",WindowsWalNativeBridge.Kernel32.class);
        Pointer handle=api.CreateFileW(new WString(Path.of(args[1]).toString()),(int)Long.parseLong(args[2]),
                Integer.parseInt(args[3]),null,3,0,null);
        if(WindowsWalNativeBridge.invalid(handle))throw WindowsWalNativeBridge.failure("holder open",Native.getLastError());
        var bridge=new WindowsWalNativeBridge(()->api,Native::getLastError);
        try {
            System.out.println("OPEN pid="+ProcessHandle.current().pid());System.out.flush();
            var input=new BufferedReader(new InputStreamReader(System.in));
            String command;
            while((command=input.readLine())!=null && !command.equals("close")) {
                if(!command.equals("observe"))throw new IllegalArgumentException("Unexpected IPC command");
                var state=bridge.standard(handle);
                System.out.println("STATE links="+state.links()+" pending="+state.deletePending()+" directory="+state.directory());System.out.flush();
            }
        } finally { bridge.close(handle); }
        System.out.println("CLOSED handles=1/1");
    }
}
