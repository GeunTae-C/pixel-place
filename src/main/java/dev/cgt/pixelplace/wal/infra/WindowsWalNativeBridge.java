package dev.cgt.pixelplace.wal.infra;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Windows WAL의 최소 ABI 경계. 호출 전까지 JNA/native 로딩 없이 생성 가능, handle은 취득 호출자가 종료 */
public class WindowsWalNativeBridge {
    static final int READ_WRITE = 0xc0000000;
    static final int BACKUP_SEMANTICS = 0x02000000;
    static final int DIRECTORY = 0x10;
    static final int REPARSE_POINT = 0x400;

    /** DWORD/BOOL은 32bit, HANDLE은 pointer 크기. 명시 W 함수만 사용 */
    interface Kernel32 extends StdCallLibrary {
        Pointer CreateFileW(WString path, int access, int share, Pointer security, int creation, int flags, Pointer template);
        int FlushFileBuffers(Pointer handle);
        int CloseHandle(Pointer handle);
        int GetFileAttributesW(WString path);
        int GetFileInformationByHandleEx(Pointer handle, int kind, Pointer buffer, int bytes);
        int GetVolumePathNameW(WString path, char[] result, int size);
        int GetVolumeNameForVolumeMountPointW(WString root, char[] result, int size);
        int GetVolumeInformationW(WString root, char[] name, int nameSize, int[] serial, int[] maximum,
                                  int[] flags, char[] filesystem, int filesystemSize);
        int GetDriveTypeW(WString root);
    }

    private final Supplier<Kernel32> loader;
    private final IntSupplier lastError;
    private volatile Kernel32 loaded;

    public WindowsWalNativeBridge() {
        this(() -> {
            if (!System.getProperty("os.name", "").startsWith("Windows")
                    || !System.getProperty("os.arch", "").equals("amd64")) {
                // 다른 OS/ABI의 우연한 호출 성공을 지원으로 채택하지 않는 경계
                throw new UnsupportedOperationException("WAL durability requires supported Windows x64");
            }
            return Native.load("kernel32", Kernel32.class);
        }, () -> Native.getLastError());
    }

    WindowsWalNativeBridge(Supplier<Kernel32> loader, IntSupplier lastError) {
        this.loader = Objects.requireNonNull(loader);
        this.lastError = Objects.requireNonNull(lastError);
    }

    private Kernel32 api() {
        Kernel32 result = loaded;
        if (result == null) synchronized (this) {
            result = loaded;
            if (result == null) loaded = result = Objects.requireNonNull(loader.get());
        }
        return result;
    }

    /** S(D): directory handle의 flush와 close가 모두 성공해야 이름 구조 동기화 완료 */
    public void syncDirectory(Path directory) throws IOException {
        Pointer handle = open(directory, READ_WRITE, BACKUP_SEMANTICS);
        WalIo.using(() -> close(handle), () -> { flush(handle); return null; });
    }

    /** R(F): 검증한 기존 내용만 동기화. 생성/truncate/active writer 게시 책임 없음 */
    public void syncRecoveredFile(Path file) throws IOException {
        Pointer handle = open(file, READ_WRITE, 0);
        WalIo.using(() -> close(handle), () -> {
            requireRegular(standard(handle));
            flush(handle);
            return null;
        });
    }

    AttributeResult attributes(Path path) {
        int value = api().GetFileAttributesW(wide(path));
        int error = value == -1 ? lastError.getAsInt() : 0;
        return new AttributeResult(value, error);
    }

    ProbeResult probe(Path path) throws IOException {
        Pointer handle = api().CreateFileW(wide(path), 0, 7, null, 3, 0, null);
        int error = invalid(handle) ? lastError.getAsInt() : 0;
        if (invalid(handle)) return new ProbeResult(null, error);
        return WalIo.using(() -> close(handle), () -> new ProbeResult(standard(handle), 0));
    }

    /** 로컬 고정 NTFS의 volume GUID/serial 대조. 드라이브 문자만으로 지원 판정 금지 */
    String volume(Path existing) throws IOException {
        char[] root = new char[1024];
        check(api().GetVolumePathNameW(wide(existing), root, root.length), "GetVolumePathNameW");
        WString rootString = new WString(Native.toString(root));
        if (api().GetDriveTypeW(rootString) != 3) throw new IOException("WAL volume is not local fixed storage");
        char[] guid = new char[1024];
        check(api().GetVolumeNameForVolumeMountPointW(rootString, guid, guid.length), "GetVolumeNameForVolumeMountPointW");
        char[] fs = new char[64];
        int[] serial = new int[1];
        check(api().GetVolumeInformationW(rootString, null, 0, serial, new int[1], new int[1], fs, fs.length), "GetVolumeInformationW");
        if (!"NTFS".equals(Native.toString(fs))) throw new IOException("WAL volume is not NTFS");
        return Native.toString(guid) + ":" + Integer.toUnsignedString(serial[0]);
    }

    Pointer open(Path path, int access, int flags) throws IOException {
        Pointer handle = api().CreateFileW(wide(path), access, 7, null, 3, flags, null);
        int error = invalid(handle) ? lastError.getAsInt() : 0;
        if (invalid(handle)) throw failure("CreateFileW", error);
        return handle;
    }

    FileStandard standard(Pointer handle) throws IOException {
        try (Memory buffer = new Memory(24)) {
            check(api().GetFileInformationByHandleEx(handle, 1, buffer, 24), "GetFileInformationByHandleEx");
            // 성공한 buffer만 해석. BOOLEAN은 1byte, DWORD link count는 unsigned
            return new FileStandard(Integer.toUnsignedLong(buffer.getInt(16)), buffer.getByte(20) != 0, buffer.getByte(21) != 0);
        }
    }

    /** Windows JDK basic fileKey는 null이므로 volume serial + 128bit file ID로 교체 감지 */
    String identity(Path path, boolean directory) throws IOException {
        Pointer handle = open(path, 0, directory ? BACKUP_SEMANTICS : 0);
        return WalIo.using(() -> close(handle), () -> {
            try (Memory buffer = new Memory(24)) {
                check(api().GetFileInformationByHandleEx(handle, 18, buffer, 24), "GetFileInformationByHandleEx(FileIdInfo)");
                return java.util.HexFormat.of().formatHex(buffer.getByteArray(0, 24));
            }
        });
    }

    static void requireRegular(FileStandard info) throws IOException {
        if (info.links() == 0 || info.deletePending() || info.directory()) {
            // 새 handle의 삭제 대기/비정상 파일을 복구 내용으로 게시하면 안 됨
            throw new IOException("WAL handle is not a live regular file");
        }
    }

    void flush(Pointer handle) throws IOException { check(api().FlushFileBuffers(handle), "FlushFileBuffers"); }
    void close(Pointer handle) throws IOException { check(api().CloseHandle(handle), "CloseHandle"); }

    private void check(int result, String operation) throws IOException {
        if (result == 0) {
            // 실패 직후 같은 스레드의 last-error 확보. close/로그로 덮어쓰기 전 보존
            int error = lastError.getAsInt();
            throw failure(operation, error);
        }
    }

    static IOException failure(String operation, int error) {
        return new IOException(operation + " failed. win32=" + Integer.toUnsignedString(error));
    }
    static boolean invalid(Pointer handle) { return handle == null || Pointer.nativeValue(handle) == 0 || Pointer.nativeValue(handle) == -1L; }
    private static WString wide(Path path) { return new WString(path.toString()); }
    record AttributeResult(int value, int error) { }
    record ProbeResult(FileStandard info, int error) { }
    record FileStandard(long links, boolean deletePending, boolean directory) { }
}
