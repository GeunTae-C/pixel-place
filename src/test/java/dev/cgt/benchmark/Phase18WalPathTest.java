package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** F 지원 확장이 앱 WAL·다른 session·build 정리 경계를 해제하지 않는지 검증 */
class Phase18WalPathTest {
    private ObjectNode plan(String wal) throws Exception {
        var tree=(ObjectNode)Phase18Plan.JSON.valueToTree(Phase18PlanTest.valid());
        ((ObjectNode)tree.path("ownership")).put("wal",wal);
        return tree;
    }
    private Phase18Plan value(ObjectNode tree){return Phase18Plan.JSON.treeToValue(tree,Phase18Plan.class);}
    @Test void acceptsLegacyCAndDedicatedFWithoutCreatingEitherSession() throws Exception {
        Path repo=Path.of("").toAbsolutePath();
        for(String wal:new String[]{repo.resolve("data/phase18-contract-fixture/run01/pixel-place.wal").toString(),
                "F:/pixel-place-wal/phase18/phase18-contract-fixture/run01/pixel-place.wal"}){
            var p=value(plan(wal));p.validate();Phase18Environment.walBudget(p);
            assertFalse(Files.exists(Path.of(wal).getParent()));
        }
    }
    @Test void rejectsRuntimeOtherSessionUnsupportedDriveAliasAndWrongRun() throws Exception {
        String repo=Path.of("").toAbsolutePath().toString();
        for(String wal:new String[]{repo+"/data/wal/pixel-place.wal",
                "F:/pixel-place-wal/runtime/pixel-place.wal",
                "F:/pixel-place-wal/phase18/phase18-other/run01/pixel-place.wal",
                "F:/pixel-place-wal/phase18/phase18-contract-fixture/other/pixel-place.wal",
                "F:/pixel-place-wal/phase18/phase18-contract-fixture/run01/other.wal",
                "E:/pixel-place-wal/phase18/phase18-contract-fixture/run01/pixel-place.wal",
                "F:/pixel-place-wal/phase18/phase18-contract-fixture/run01/../pixel-place.wal",
                "F:/pixel-place-wal/phase18/phase18-contract-fixture/run01/pixel-place.wal:stream"})
            assertThrows(IllegalArgumentException.class,()->value(plan(wal)).validate(),wal);
    }
    @Test void rejectsBuildRootOverlappingWalSession() throws Exception {
        var tree=plan("F:/pixel-place-wal/phase18/phase18-contract-fixture/run01/pixel-place.wal");
        var owner=(ObjectNode)tree.path("ownership");
        String root="F:/pixel-place-wal/phase18/phase18-contract-fixture";
        owner.put("sessionRoot",root);
        for(String area:new String[]{"build","cache","temp","jna"})owner.put(area,root+"/"+area);
        assertThrows(IllegalArgumentException.class,()->value(tree).validate());
    }
    @Test void walBudgetCountsOtherRunBytesInOwnedSession() throws Exception {
        String session="phase18-wal-budget-"+UUID.randomUUID();
        Path root=Path.of("").toAbsolutePath().resolve("data").resolve(session);
        var tree=plan(root.resolve("run01/pixel-place.wal").toString());tree.put("sessionId",session);
        var p=value(tree);
        // 대상 run만 세면 놓치는 같은 session의 잔여 파일도 0-byte 예약을 초과해야 함
        Files.createDirectories(root.resolve("older-run"));
        Files.write(root.resolve("older-run/fixture.bin"),new byte[]{1},StandardOpenOption.CREATE_NEW);
        assertThrows(IllegalArgumentException.class,()->Phase18Environment.walBudget(p));
    }
}
