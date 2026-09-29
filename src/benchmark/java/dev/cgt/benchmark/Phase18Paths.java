package dev.cgt.benchmark;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/** 소유 root와 별칭 없는 Windows 절대 경로 경계. production WAL 지원 판정 책임 없음 */
public final class Phase18Paths {
    private Phase18Paths() { }

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

    public static long usable(Path path) throws IOException {
        while (!Files.exists(path)) path = path.getParent();
        return Files.getFileStore(path).getUsableSpace();
    }
}
