package dev.cgt.benchmark;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** 별도 비교 role의 허용 조합과 원래 CAP role 경계. 앱 부하를 실행하지 않는 선택 검증 */
class Phase18Cap1ComparisonTest {
    @TempDir Path temporary;
    private Phase18Plan plan(long rate,long seconds,String hash,String variant,int slot)throws Exception {
        var node=(ObjectNode)Phase18Plan.JSON.valueToTree(Phase18Cap1Test.plan(rate,seconds));
        node.put("schemaVersion",4);
        ((ObjectNode)node.path("cases").get(0)).put("role","cap-diagnostic-ab");
        var policy=Phase18Plan.JSON.createObjectNode();policy.put("role","cap-diagnostic-ab");
        policy.putObject("instruction").put("sha256",hash);
        policy.putObject("comparison").put("variant",variant).put("slot",slot);
        policy.putObject("cap1Profile").put("role","cap-diagnostic-ab").put("writeRate",rate).put("measurementSeconds",seconds);
        Path path=Files.createTempFile(temporary,"policy-",".json");Files.writeString(path,policy.toString());
        ((ObjectNode)node.path("predecessor")).put("path",path.toString()).put("sha256",BenchmarkJson.hash(path));
        var p=Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
        var b=(ObjectNode)node.path("bounds");var output=Phase18Cap1.output(p);
        b.put("outputBytes",Phase18Cap1.limit(256L*1024*1024,output.runBytes()));
        b.put("buildBytes",Phase18Cap1.limit(2048L*1024*1024,Phase18Cap1.workspaceUsage(p)));
        return Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
    }
    private static final String HASH="b892240cf15c9cd4ff2f80fe1e86841bcc04680ff877938d2eba010a41cb8859";
    @Test void exactDiagnosticCombinationsAcceptWithoutChangingFormalRole()throws Exception {
        for(long[] pair:new long[][]{{105,120},{105,1200},{500,60}}){
            var p=plan(pair[0],pair[1],HASH,"A",1);p.validate();Phase18K6Session.requireSupport(p);
            assertTrue(Phase18Cap1.comparison(p));
        }
        assertFalse(Phase18Cap1.comparison(Phase18Cap1Test.plan(105,1200)));
    }
    @Test void wrongInstructionVariantSlotAndUnapprovedCombinationReject()throws Exception {
        for(var p:java.util.List.of(plan(105,120,"0".repeat(64),"A",1),plan(105,120,HASH,"C",1),
                plan(105,120,HASH,"A",9),plan(500,120,HASH,"A",1),plan(250,60,HASH,"A",1))){
            assertThrows(IllegalArgumentException.class,p::validate);
        }
    }
}
