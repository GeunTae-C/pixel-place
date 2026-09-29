package dev.cgt.pixelplace.wal.infra;

import java.io.Closeable;
import java.io.IOException;

/** WAL 자원 소유 경계의 Error 우선·동일 원인 보존. 정리 실패를 성공으로 바꾸지 않음 */
final class WalIo {
    private WalIo() { }

    static Throwable combine(Throwable first, Throwable next) {
        if (first == null) return next;
        if (next == null) return first;
        if (first == next) return first;
        Throwable primary = first instanceof Error || !(next instanceof Error) ? first : next;
        Throwable secondary = primary == first ? next : first;
        for (Throwable suppressed : primary.getSuppressed()) if (suppressed == secondary) return primary;
        primary.addSuppressed(secondary);
        return primary;
    }

    static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof IOException io) throw io;
    }

    static <T> T using(Closeable resource, Action<T> action) throws IOException {
        T result = null;
        Throwable failure = null;
        try { result = action.run(); }
        catch (IOException | RuntimeException | Error problem) { failure = problem; }
        try { resource.close(); }
        catch (IOException | RuntimeException | Error problem) { failure = combine(failure, problem); }
        rethrow(failure);
        return result;
    }

    @FunctionalInterface interface Action<T> { T run() throws IOException; }
}
