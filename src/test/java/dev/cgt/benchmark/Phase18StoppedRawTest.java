package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** 중단 정리의 미송신 수용이 정상 실행/UNKNOWN 판정 완화로 번지지 않도록 고정 */
class Phase18StoppedRawTest {
    @TempDir Path directory;
    @Test void stoppedCleanupAcceptsOnlyExactUnsentWhileNormalAndUnknownStillFail()throws Exception {
        ObjectNode plan=(ObjectNode)Phase18Plan.JSON.valueToTree(Phase18PlanTest.valid());
        plan.put("state","draft");
        var c=(ObjectNode)plan.path("cases").get(0);c.put("caseId","ordinary-read");c.put("kind","read");
        ((ObjectNode)c.path("workload")).put("readRate",1);
        Path input=directory.resolve("plan.json");Files.writeString(input,plan.toString());var loaded=Phase18Plan.read(input);
        var actual=loaded.plan().cases().getFirst();Path raw=directory.resolve("raw.jsonl");
        var row=new Phase18Raw(1,loaded.planHash(),actual.runId(),actual.caseId(),Phase18Plan.requestId(actual,"read","measurement",0),
                "read","measurement",0,"fixture",0,1,null,null,"client_not_sent","stopped",null,null,null,
                "not_sent","NOT_SENT","not_applicable",null,null,0,0,0,null,null,null);
        Files.writeString(raw,Phase18Plan.JSON.writeValueAsString(row)+"\n");
        assertThrows(IllegalArgumentException.class,()->Phase18Consistency.read(raw,loaded));
        assertEquals(1,Phase18Consistency.readStopped(raw,loaded).size());
        ObjectNode unknown=(ObjectNode)Phase18Plan.JSON.valueToTree(row);unknown.put("status","timeout");unknown.put("sendState","sent");unknown.put("outcome","UNKNOWN");
        Files.writeString(raw,unknown.toString()+"\n");
        assertThrows(IllegalArgumentException.class,()->Phase18Consistency.readStopped(raw,loaded));
    }
}
