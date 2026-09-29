package dev.cgt.pixelplace.wal.infra;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 로컬 NTFS 경로 준비와 Q 판정 소유. storage별 준비 증명을 반환하며 전역 준비 캐시를 갖지 않음 */
@Component
public class WindowsWalFileDurability implements WalFileDurability {
    private final WindowsWalNativeBridge bridge;

    public WindowsWalFileDurability() { this(new WindowsWalNativeBridge()); }
    WindowsWalFileDurability(WindowsWalNativeBridge bridge) { this.bridge = Objects.requireNonNull(bridge); }

    /** 기준점 S 뒤 자식을 하나씩 준비. 부분 실패 흔적 자동 삭제 금지 */
    @Override
    public DirectoryProof prepareDirectory(Path directory) throws IOException {
        Path target = directory.toAbsolutePath().normalize();
        Path anchor = target.getParent() == null ? target : target.getParent();
        while (true) {
            try { directoryStamp(anchor); break; }
            catch (NoSuchFileException absent) {
                if (anchor.getParent() == null) throw absent;
                anchor = anchor.getParent();
            }
        }
        List<DirectoryStamp> chain = inspectChain(anchor);
        String volume = bridge.volume(anchor);
        bridge.syncDirectory(anchor);
        Path parent = anchor;
        for (Path part : anchor.relativize(target)) {
            if (part.toString().isEmpty()) continue;
            Path child = parent.resolve(part);
            try { directoryStamp(child); }
            catch (NoSuchFileException absent) {
                createDirectory(child);
                // 새 이름을 담은 부모부터 동기화해야 다음 하위 이름을 준비할 수 있음
                bridge.syncDirectory(parent);
            }
            DirectoryStamp stamp = directoryStamp(child);
            if (!volume.equals(bridge.volume(child))) throw new IOException("WAL child volume changed");
            bridge.syncDirectory(child);
            chain.add(stamp);
            parent = child;
        }
        DirectoryProof result = new DirectoryProof(target, chain, volume);
        verifyDirectory(result);
        return result;
    }

    /** 경로 identity/볼륨/reparse가 달라졌으면 이전 준비 성공 재사용 금지 */
    @Override
    public void verifyDirectory(DirectoryProof proof) throws IOException {
        for (DirectoryStamp previous : proof.chain()) {
            if (!previous.equals(directoryStamp(previous.path()))) throw new IOException("Prepared WAL directory identity changed");
        }
        if (!proof.volume().equals(bridge.volume(proof.directory()))) throw new IOException("Prepared WAL volume changed");
    }

    @Override
    public void syncDirectory(DirectoryProof proof) throws IOException {
        verifyDirectory(proof);
        bridge.syncDirectory(proof.directory());
    }

    @Override
    public void syncRecoveredFile(Path file) throws IOException {
        fileIdentity(file);
        bridge.syncRecoveredFile(file);
    }

    @Override
    public String fileIdentity(Path file) throws IOException {
        BasicFileAttributes java = attributes(file);
        var nativeAttributes = bridge.attributes(file);
        if (!java.isRegularFile() || java.isSymbolicLink() || nativeAttributes.error() != 0
                || (nativeAttributes.value() & (WindowsWalNativeBridge.DIRECTORY | WindowsWalNativeBridge.REPARSE_POINT)) != 0) {
            throw WindowsWalNativeBridge.failure("Recovery file validation", nativeAttributes.error());
        }
        return bridge.identity(file, false);
    }

    /** Q: 안전한 부모 여부와 원인을 함께 반환하여 실패 뒤 필요한 S 시도를 storage가 결정 */
    @Override
    public NameObservation queryName(Path file, DirectoryProof proof) {
        boolean parentSafe = false;
        try {
            if (!file.getParent().equals(proof.directory())) throw new IOException("WAL query parent mismatch");
            verifyDirectory(proof);
            parentSafe = true;
            boolean listed = listed(file, proof.directory());
            var nativeAttributes = bridge.attributes(file);
            var probe = bridge.probe(file);
            BasicFileAttributes java = null;
            try { java = attributes(file); }
            catch (NoSuchFileException absent) { /* 명시적 Java 부재는 다른 세 관측과 함께만 채택 */ }
            verifyDirectory(proof);
            if (!listed && nativeAttributes.error() == 2 && probe.error() == 2 && java == null) {
                return new NameObservation(NameState.ABSENT, true, null);
            }
            boolean nativeRegular = nativeAttributes.error() == 0
                    && (nativeAttributes.value() & (WindowsWalNativeBridge.DIRECTORY | WindowsWalNativeBridge.REPARSE_POINT)) == 0;
            if (listed && nativeRegular && probe.error() == 0 && java != null && java.isRegularFile() && !java.isSymbolicLink()) {
                WindowsWalNativeBridge.requireRegular(probe.info());
                return new NameObservation(NameState.PRESENT, true, null);
            }
            throw new IOException("WAL name state is uncertain. attributesError=" + nativeAttributes.error() + ", openError=" + probe.error());
        } catch (IOException | RuntimeException | Error failure) {
            Throwable combined = failure;
            // 부모 재검증 실패는 이전 safe 관측을 무효화. 다른 부모를 S하여 성공 처리 금지
            if (parentSafe) {
                try { verifyDirectory(proof); }
                catch (IOException | RuntimeException | Error unsafe) { parentSafe = false; combined = WalIo.combine(failure, unsafe); }
            }
            return new NameObservation(NameState.UNKNOWN, parentSafe, combined);
        }
    }

    private boolean listed(Path file, Path parent) throws IOException {
        DirectoryStream<Path> entries = openDirectory(parent);
        return WalIo.using(entries, () -> {
            for (Path entry : entries) if (entry.getFileName().toString().equalsIgnoreCase(file.getFileName().toString())) return true;
            return false;
        });
    }

    private List<DirectoryStamp> inspectChain(Path directory) throws IOException {
        List<DirectoryStamp> result = new ArrayList<>();
        Path current = directory.getRoot();
        result.add(directoryStamp(current));
        for (Path part : directory) {
            current = current.resolve(part);
            result.add(directoryStamp(current));
        }
        return result;
    }

    private DirectoryStamp directoryStamp(Path path) throws IOException {
        BasicFileAttributes java = attributes(path);
        var nativeAttributes = bridge.attributes(path);
        if (nativeAttributes.error() != 0) throw WindowsWalNativeBridge.failure("Directory attributes", nativeAttributes.error());
        if (!java.isDirectory() || java.isSymbolicLink()
                || (nativeAttributes.value() & WindowsWalNativeBridge.DIRECTORY) == 0
                || (nativeAttributes.value() & WindowsWalNativeBridge.REPARSE_POINT) != 0) {
            throw new IOException("WAL path component is not a stable non-reparse directory");
        }
        return new DirectoryStamp(path, bridge.identity(path, true));
    }

    BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
    DirectoryStream<Path> openDirectory(Path directory) throws IOException { return Files.newDirectoryStream(directory); }
    void createDirectory(Path directory) throws IOException { Files.createDirectory(directory); }
}
