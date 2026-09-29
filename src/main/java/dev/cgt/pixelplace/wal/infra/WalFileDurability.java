package dev.cgt.pixelplace.wal.infra;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** storage가 소유한 경로 준비 증명과 파일/이름 내구성 경계. production fallback 없음 */
public interface WalFileDurability {
    DirectoryProof prepareDirectory(Path directory) throws IOException;
    void verifyDirectory(DirectoryProof proof) throws IOException;
    void syncDirectory(DirectoryProof proof) throws IOException;
    void syncRecoveredFile(Path file) throws IOException;
    String fileIdentity(Path file) throws IOException;
    NameObservation queryName(Path file, DirectoryProof proof);

    enum NameState { PRESENT, ABSENT, UNKNOWN }
    record DirectoryStamp(Path path, Object key) { }
    record DirectoryProof(Path directory, List<DirectoryStamp> chain, String volume) {
        public DirectoryProof { chain = List.copyOf(chain); }
    }
    record NameObservation(NameState state, boolean parentSafe, Throwable failure) { }
}
