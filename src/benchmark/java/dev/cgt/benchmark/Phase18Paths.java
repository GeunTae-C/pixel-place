package dev.cgt.benchmark;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/** 소유 root와 별칭 없는 Windows 절대 경로 경계. production WAL 지원 판정 책임 없음 */
public final class Phase18Paths {
    private Phase18Paths() { }

    /** 새 실행의 경로만 C/F로 제한. 과거 증거 읽기용 checked의 의미는 유지 */
    static Path execution(String value) throws IOException {
        Path path = checked(value);
        if (!SetHolder.DRIVES.contains(path.getRoot().toString().toUpperCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException("C/F execution path required; historical drive forbidden");
        return path;
    }

    /** 환경·JVM 옵션에 섞인 E 경로를 값 노출 없이 거부 */
    static void rejectHistoricalExecution(String value) {
        if (value != null && java.util.regex.Pattern.compile("(?i)(?<![a-z0-9])e:[/\\\\]").matcher(value).find())
            throw new IllegalArgumentException("Historical E execution path forbidden");
    }

    private static final class SetHolder {
        private static final java.util.Set<String> DRIVES = java.util.Set.of("C:\\", "F:\\");
    }

    public static Path checked(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z]:[/\\\\].+") || value.substring(2).contains(":"))
            throw new IllegalArgumentException("Local absolute path required");
        Path p = Path.of(value);
        if (!p.equals(p.normalize())) throw new IllegalArgumentException("Path traversal forbidden");
        for (Path part : p) {
            String name = part.toString();
            if (name.endsWith(".") || name.endsWith(" ") || name.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?"))
                throw new IllegalArgumentException("Windows path alias forbidden");
        }
        for (Path n = p; n != null; n = n.getParent()) {
            if (!Files.exists(n, LinkOption.NOFOLLOW_LINKS)) continue;
            var a = Files.readAttributes(n, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || a.isOther() || !n.toRealPath().equals(n))
                throw new IllegalArgumentException("Reparse or aliased path forbidden");
        }
        return p;
    }

    public static void child(Path path, Path owner) {
        if (path.equals(owner) || !path.startsWith(owner)) throw new IllegalArgumentException("Outside exclusive owner root");
    }

    public static void disjoint(List<Path> paths) {
        for (int i = 0; i < paths.size(); i++) for (int j = i + 1; j < paths.size(); j++)
            if (paths.get(i).startsWith(paths.get(j)) || paths.get(j).startsWith(paths.get(i)))
                throw new IllegalArgumentException("Overlapping owned paths");
    }

    /** 확인한 C 기존 배치와 F 전용 session만 허용. 앱 WAL 및 다른 session 인수 차단 */
    static Path walRoot(String value, Path repo, String session) throws IOException {
        Path wal = checked(value);
        Path legacy = checked(repo.resolve("data").resolve(session).toString());
        Path dedicated = checked("F:/pixel-place-wal/phase18/" + session);
        Path owner;
        if (repo.getRoot().toString().equalsIgnoreCase("C:\\") && wal.startsWith(legacy)) owner = legacy;
        else if (wal.startsWith(dedicated)) owner = dedicated;
        else throw new IllegalArgumentException("WAL outside supported exclusive session roots");
        child(wal, owner);
        disjoint(List.of(owner, checked(repo.resolve("data/wal").toString()), checked("F:/pixel-place-wal/runtime")));
        return owner;
    }

    /** 존재하는 가장 가까운 조상의 실제 volume 검사. repo의 C 볼륨으로 F 여유/FS를 대신하지 않음 */
    static FileStore store(Path path) throws IOException {
        while (!Files.exists(path)) path = path.getParent();
        return Files.getFileStore(path);
    }

    public static long usable(Path path) throws IOException {
        return store(path).getUsableSpace();
    }
}
