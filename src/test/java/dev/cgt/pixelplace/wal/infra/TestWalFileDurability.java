package dev.cgt.pixelplace.wal.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** unit/MySQL fixture의 native 경계만 명시 대체. 실제 FileChannel/JSONL scan은 production storage 사용 */
public class TestWalFileDurability implements WalFileDurability {
    public final List<String> events = new ArrayList<>();

    @Override public DirectoryProof prepareDirectory(Path directory) throws IOException {
        events.add("prepare");
        Files.createDirectories(directory);
        return new DirectoryProof(directory, List.of(), "test-only");
    }
    @Override public void verifyDirectory(DirectoryProof proof) throws IOException {
        if (!Files.isDirectory(proof.directory(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Fixture parent changed");
    }
    @Override public void syncDirectory(DirectoryProof proof) throws IOException {
        verifyDirectory(proof); events.add("S");
    }
    @Override public void syncRecoveredFile(Path file) throws IOException {
        fileIdentity(file); events.add("R:" + file.getFileName());
    }
    @Override public String fileIdentity(Path file) throws IOException {
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw new IOException("Fixture file changed");
        return file.toRealPath() + ":" + attributes.creationTime();
    }
    @Override public NameObservation queryName(Path file, DirectoryProof proof) {
        events.add("Q");
        try {
            verifyDirectory(proof);
            try { fileIdentity(file); return new NameObservation(NameState.PRESENT,true,null); }
            catch (NoSuchFileException absent) { return new NameObservation(NameState.ABSENT,true,null); }
        } catch (IOException | RuntimeException | Error failure) { return new NameObservation(NameState.UNKNOWN,false,failure); }
    }
}
