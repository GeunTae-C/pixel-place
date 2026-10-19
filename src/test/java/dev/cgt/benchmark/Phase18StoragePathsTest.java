package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 선언된 task 속성 대신 실제 test JVM·fixture 생성 위치 확인 */
class Phase18StoragePathsTest {
    @TempDir Path fixture;

    @Test void actualTestJvmAndFixtureUseFWorkStorage() throws Exception {
        Path root=Path.of("F:/pixel-place-work");
        for (String name : new String[]{"java.io.tmpdir","jna.tmpdir"}) {
            Path value=Phase18Paths.checked(System.getProperty(name));
            assertTrue(value.startsWith(root),name+"="+value);
        }
        assertTrue(fixture.toAbsolutePath().startsWith(root));
        Path probe=fixture.resolve("actual-output.txt");
        Files.writeString(probe,"F test JVM output");
        assertEquals("F test JVM output",Files.readString(probe));
        System.out.println("ACTUAL_F_TEST_TEMP="+System.getProperty("java.io.tmpdir"));
        System.out.println("ACTUAL_F_TEST_JNA="+System.getProperty("jna.tmpdir"));
    }
}
