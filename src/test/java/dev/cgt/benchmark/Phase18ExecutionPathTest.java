package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 새 실행의 F 소유 경로와 상속 환경 검사. 과거 증거 분석 경로와 실행 경로 구분 */
class Phase18ExecutionPathTest {
    @Test void acceptsFrozenFPathsAndRejectsEachInheritedEPath() throws Exception {
        var plan = Phase18PlanTest.valid();
        var environment = new HashMap<String,String>();
        environment.put("TEMP", plan.ownership().temp()); environment.put("TMP", plan.ownership().temp());
        environment.put("GRADLE_USER_HOME", "F:/pixel-place-work/gradle-home");
        var properties = new Properties(); properties.setProperty("java.io.tmpdir", plan.ownership().temp());
        properties.setProperty("jna.tmpdir", plan.ownership().jna());
        Phase18Runtime.verifyExecutionPaths(plan, environment, properties);
        for (String name : List.of("PATH", "JAVA_HOME", "GRADLE_USER_HOME", "GRADLE_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "TEMP", "TMP", "CLASSPATH", "PIXEL_PLACE_WAL_ACTIVE_FILE")) {
            var bad = new HashMap<>(environment); bad.put(name, "-Dpath=e:/old/cache");
            var error = assertThrows(IllegalArgumentException.class, () -> Phase18Runtime.verifyExecutionPaths(plan, bad, properties));
            assertTrue(error.getMessage().contains("Historical E"), name);
        }
        properties.setProperty("jna.tmpdir", "F:/pixel-place-work/sessions/another/jna");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Phase18Runtime.verifyExecutionPaths(plan, environment, properties)).getMessage().contains("temp/JNA mismatch"));
    }

    @Test void rejectsOldOrWrongSessionRootAndWrongWorkArea() throws Exception {
        var plan = Phase18PlanTest.valid(); plan.validate();
        for (String root : List.of("E:/pixel-place-phase15/phase18-contract-fixture", "F:/MySQL/test/phase18-contract-fixture",
                "F:/pixel-place-work/sessions/phase18-other")) {
            var tree = (ObjectNode) Phase18Plan.JSON.valueToTree(plan);
            ((ObjectNode)tree.path("ownership")).put("sessionRoot", root);
            assertThrows(IllegalArgumentException.class, () -> Phase18Plan.JSON.treeToValue(tree, Phase18Plan.class).validate());
        }
        var tree = (ObjectNode) Phase18Plan.JSON.valueToTree(plan);
        ((ObjectNode)tree.path("ownership")).put("cache", plan.ownership().sessionRoot()+"/other-cache");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Phase18Plan.JSON.treeToValue(tree, Phase18Plan.class).validate()).getMessage().contains("work areas"));
    }

    @Test void historicalReferenceSyntaxRemainsSeparateFromExecutionPermission() throws Exception {
        assertEquals(Path.of("E:/archived-evidence/plan.json"), Phase18Paths.checked("E:/archived-evidence/plan.json"));
        assertThrows(IllegalArgumentException.class, () -> Phase18Paths.execution("E:/archived-evidence/plan.json"));
        Phase18Paths.rejectHistoricalExecution("file://C:/evidence");
        assertThrows(IllegalArgumentException.class, () -> Phase18Paths.rejectHistoricalExecution("C:/bin;e:\\old\\bin"));
    }

    @Test void rejectsHistoricalInputFilesBeforeReadingThem() {
        Path absent = Path.of("E:/never-read-current-execution/input.json");
        assertThrows(IllegalArgumentException.class, () -> Phase18Plan.read(absent));
        assertThrows(IllegalArgumentException.class, () -> Phase18Environment.read(absent, null));
        assertThrows(IllegalArgumentException.class, () -> Phase18ObservationPolicy.read(absent, null));
    }

    @Test void toolsChildArgumentsPreserveActualTempAndJna(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Path args = directory.resolve("tools.args");
        Phase18Tools.command(Path.of(System.getProperty("java.home"), "bin/java.exe"), args,
                directory.resolve("child"), "normal", 3000, 65536);
        String text = java.nio.file.Files.readString(args);
        assertTrue(text.contains(Phase18Tools.quoteJavaArgument("-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"))));
        assertTrue(text.contains(Phase18Tools.quoteJavaArgument("-Djna.tmpdir="+System.getProperty("jna.tmpdir"))));
    }
}
