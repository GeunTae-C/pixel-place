package dev.cgt.pixelplace.wal.infra;

import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.file.Path;

/** 실제 MySQL 사례의 OS 경계만 대체. production R 소유/flush/close 알고리즘과 startup 순서 검증 */
public final class RecoveryBridgeTestDurability extends TestWalFileDurability {
    public int opened, closed;
    public Runnable beforeIo=()->{};
    private final String failure;
    private final WindowsWalNativeBridge bridge;

    public RecoveryBridgeTestDurability(String failure) {
        this.failure=failure;
        bridge=new WindowsWalNativeBridge() {
            @Override Pointer open(Path file,int access,int flags) {
                opened++;events.add("R-open");return Pointer.createConstant(opened+100L);
            }
            @Override FileStandard standard(Pointer handle) {return new FileStandard(1,false,false);}
            @Override void flush(Pointer handle) throws IOException {
                events.add("R-flush");if(failure.equals("R-flush"))throw new IOException("Injected R-flush");
            }
            @Override void close(Pointer handle) throws IOException {
                closed++;events.add("R-close");if(failure.equals("R-close"))throw new IOException("Injected R-close");
            }
        };
    }
    @Override public void syncRecoveredFile(Path file) throws IOException {
        beforeIo.run();fileIdentity(file);bridge.syncRecoveredFile(file);events.add("R-complete");
    }
    @Override public void syncDirectory(DirectoryProof proof) throws IOException {
        beforeIo.run();super.syncDirectory(proof);
        if(failure.equals("last-S"))throw new IOException("Injected last-S");
    }
}
