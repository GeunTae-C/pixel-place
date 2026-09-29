package dev.cgt.pixelplace.wal.infra;

import com.sun.jna.Pointer;
import dev.cgt.benchmark.Phase17CTrace;
import java.io.IOException;
import java.nio.file.Path;

/** benchmark에서만 명시 생성하는 실제 Windows bridge 위임 관측. 생성 중 native I/O 없음 */
public final class Phase17CWindowsObservation extends WindowsWalFileDurability {
    private final Phase17CTrace trace;
    public Phase17CWindowsObservation(Phase17CTrace trace) { super(new Bridge(trace)); this.trace = trace; }
    @Override public void syncRecoveredFile(Path file) throws IOException { observed("R", file.toString(), () -> { super.syncRecoveredFile(file); return null; }); }
    @Override public void syncDirectory(DirectoryProof proof) throws IOException { observed("S", proof.directory().toString(), () -> { super.syncDirectory(proof); return null; }); }
    @Override public DirectoryProof prepareDirectory(Path directory) throws IOException {
        return observed("prepareDirectory", directory.toString(), () -> super.prepareDirectory(directory));
    }
    @Override public NameObservation queryName(Path file, DirectoryProof proof) {
        long id = trace.begin("Q", file.toString()); NameObservation result = null; Throwable failure = null;
        try { result = super.queryName(file, proof); return result; }
        catch (RuntimeException | Error problem) { failure = problem; throw problem; }
        finally { trace.end(id, "Q", file.toString(), result == null ? "absent-result" : result.state() + ":parentSafe=" + result.parentSafe(), failure == null && result != null ? result.failure() : failure); }
    }
    private <T> T observed(String operation, String path, WalIo.Action<T> action) throws IOException {
        long id = trace.begin(operation, path); Throwable failure = null;
        try { return action.run(); } catch (IOException | RuntimeException | Error problem) { failure = problem; throw problem; }
        finally { trace.end(id, operation, path, "completed", failure); }
    }
    /** 기본 bridge의 실제 kernel32 로딩/호출/오류 결합을 그대로 사용. handle 원문 payload 없음 */
    private static final class Bridge extends WindowsWalNativeBridge {
        private final Phase17CTrace trace;
        private Bridge(Phase17CTrace trace) { this.trace = trace; }
        @Override Pointer open(Path path, int access, int flags) throws IOException {
            long id = trace.begin("native.open", path.toString()); Pointer result = null; Throwable failure = null;
            try { result = super.open(path, access, flags); return result; }
            catch (IOException | RuntimeException | Error problem) { failure = problem; throw problem; }
            finally { trace.end(id, "native.open", path.toString(), identity(result), failure); }
        }
        @Override void flush(Pointer handle) throws IOException { call("native.flush", handle, () -> super.flush(handle)); }
        @Override void close(Pointer handle) throws IOException { call("native.close", handle, () -> super.close(handle)); }
        @Override public void syncDirectory(Path path) throws IOException {
            long id = trace.begin("native.S", path.toString()); Throwable failure = null;
            try { super.syncDirectory(path); } catch (IOException | RuntimeException | Error problem) { failure = problem; throw problem; }
            finally { trace.end(id, "native.S", path.toString(), "completed", failure); }
        }
        private void call(String op, Pointer handle, Io action) throws IOException {
            String target = identity(handle); long id = trace.begin(op, target); Throwable failure = null;
            try { action.run(); } catch (IOException | RuntimeException | Error problem) { failure = problem; throw problem; }
            finally { trace.end(id, op, target, "completed", failure); }
        }
        private static String identity(Pointer p) { return p == null ? "invalid" : Long.toUnsignedString(Pointer.nativeValue(p)); }
        @FunctionalInterface private interface Io { void run() throws IOException; }
    }
}
