"""확정된 GC-08 single 선택에 따른 한 번의 제한 소스 정리. 측정/DB/Git 작업 없음."""
from pathlib import Path
import json

root=Path.cwd()
decision=json.loads(Path('E:/pixel-place-phase15/phase15-a-20260918/agent-runs/phase15-C-4-20260923-analysis-r3/decision.json').read_text(encoding='utf-8'))
assert decision['adoption']=='single' and decision['comparisonComplete']
changes={}
def content(name):return (root/name).read_text(encoding='utf-8')
def replace(text,old,new):
    assert text.count(old)==1,old[:100]
    return text.replace(old,new,1)
def cut(text,start,end):
    assert text.count(start)==text.count(end)==1
    a=text.index(start);b=text.index(end,a);return text[:a]+text[b:]

base='src/main/java/dev/cgt/pixelplace/'
p=base+'pixel/application/PixelWriteService.java';s=content(p)
s=cut(s,'    /** caller가 coordinator를 보유한 채 호출.', '    static void validateWriteRequest(')
for line in ('import java.util.ArrayList;\n','import java.util.List;\n','import java.util.Objects;\n','import java.util.function.Consumer;\n'):s=replace(s,line,'')
changes[p]=s
p=base+'pixel/application/EventSeqManager.java';s=content(p)
s=cut(s,'    /** batch 준비 전 고갈 확인.', '    /*\n     * recovery 검증과 운영 관측')
changes[p]=s
p=base+'pixel/application/WriteExecutionProperties.java'
changes[p]='''package dev.cgt.pixelplace.pixel.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 최종 single cold-start 계약. 폐기된 group 입력을 조용히 무시하지 않고 부팅 전에 거부 */
@ConfigurationProperties(prefix = "pixel-place.write", ignoreUnknownFields = false)
public class WriteExecutionProperties {
    private String mode = "single";
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    /** stub 포함 모든 profile의 단일 실행 전략과 폐기 설정 오사용 차단 */
    public void validate() {
        if (!"single".equals(mode)) throw new IllegalArgumentException("Only single write mode is supported");
    }
}
'''
p=base+'pixel/web/PixelController.java';s=content(p)
s=replace(s,'import dev.cgt.pixelplace.pixel.application.PixelWriteUnknownException;\n','')
s=cut(s,'    /* 처리 불확실성 전달.', '    /* gate/queue 대기 실패')
s=replace(s,'/* gate/queue 대기 실패','/* 사용자 gate 대기 실패')
changes[p]=s
p=base+'measurement/PixelMeasurement.java';s=content(p)
s=replace(s,'    private long singleCalls, batchCalls, batchRecords, completedRecords, recordForces,','    private long singleCalls, completedRecords, recordForces,')
s=replace(s,'    private final long[] batchSizes = new long[130];\n','')
s=replace(s,'''    public synchronized void walStarted(boolean batch, int records) {
        if (batch) { batchCalls++; batchRecords += records; if (enabled) batchSizes[Math.min(129, records)]++; }
        else singleCalls++;
    }''','''    public synchronized void walSingleStarted() { singleCalls++; }''')
a=s.index('        java.util.Map<Integer, Long> sizes = null;',s.index('public synchronized WalCounts walCounts()'))
b=s.index('    /** timer의 완료 count',a)
s=s[:a]+'''        // 보존된 비교 JSON과 공통 분석 schema 호환. runtime batch 수집/실행 경로는 없음
        return new WalCounts(singleCalls, 0, 0, completedRecords,
                recordForces, coveredRecords, emptyForces, failedForces, rotations, enabled ? java.util.Map.of() : null);
    }
'''+s[b:]
s=replace(s,'/** claim 시각처럼 경계 안에서 얻은 완료 시각을 사용하여 게시/계측 지연을 queue wait에서 제외 */','/** 호출 경계에서 확보한 완료 시각으로 계측 처리 자체의 시간을 제외 */')
changes[p]=s

p='src/benchmark/java/dev/cgt/benchmark/Phase15BenchmarkMain.java';s=content(p)
s=replace(s,'BenchmarkSpec.require(Set.of("single", "group").contains(writeMode), "write mode");','BenchmarkSpec.require(writeMode.equals("single"), "Group comparison requires the preserved C4 candidate; final runtime is single");')
for line in ('        properties.put("pixel-place.write.group.max-batch-size", 16);\n','        properties.put("pixel-place.write.group.max-outstanding", 128);\n','        properties.put("pixel-place.write.group.queue-timeout", "1s");\n','        properties.put("pixel-place.write.group.shutdown-grace", "10s");\n'):s=replace(s,line,'')
s=replace(s,'Class<?> expected = writeMode.equals("group") ? GroupPixelWriteExecutor.class : SinglePixelWriteExecutor.class;','Class<?> expected = SinglePixelWriteExecutor.class;')
a=s.index('        if (!configuration.getMode().equals(writeMode)');b=s.index('            throw new IllegalStateException("Effective write configuration mismatch");',a)
s=s[:a]+'        if (!configuration.getMode().equals(writeMode))\n'+s[b:]
s=replace(s,'''        return Map.of("mode", writeMode, "maxBatchSize", 16, "maxOutstanding", 128,
                "queueTimeoutMillis", 1000, "shutdownGraceMillis", 10000, "collectionWaitMillis", 0);''','''        return Map.of("mode", writeMode);''')
changes[p]=s

tests='src/test/java/dev/cgt/pixelplace/'
p=tests+'pixel/application/EventSeqExhaustionTest.java';changes[p]=replace(content(p),'        assertThrows(IllegalStateException.class,()->seq.requireCapacity(1));\n','')
p=tests+'pixel/application/PixelWriteServiceTest.java';s=content(p)
s=replace(s,'        @Override\n        public void appendBatchAndFsync(List<WalRecord> batch) { records.addAll(List.copyOf(batch)); }\n','')
s=replace(s,'''        @Override
        public void appendBatchAndFsync(List<WalRecord> batch) {
            assertEquals(BoardConstants.DEFAULT_COLOR_INDEX, board.getRequired(tileKey).pixels()[pixelIndex]);
            callCount += batch.size();
        }

''','')
changes[p]=s
p=tests+'pixel/application/PixelCommandRedisIntegrationTest.java';s=content(p)
s=replace(s,'verifyMode(false);','verifySingle();')
s=cut(s,'    @Test\n    void realRedisGroup', '    private void verifyMode(boolean group)')
s=replace(s,'private void verifyMode(boolean group)','private void verifySingle()')
s=replace(s,'runCompetition(new StringRedisTemplate(factory),group);','runCompetition(new StringRedisTemplate(factory));')
s=replace(s,'private void runCompetition(StringRedisTemplate redis,boolean group)','private void runCompetition(StringRedisTemplate redis)')
a=s.index('        PixelWriteExecutor executor=group?');b=s.index('        var command =',a)
s=s[:a]+'''        PixelWriteExecutor executor=new SinglePixelWriteExecutor(boundary,core,dirty,readiness,dev.cgt.pixelplace.measurement.Measurements.disabled());
'''+s[b:]
s=replace(s,'        doAnswer(call -> { entered.countDown(); await(release); return null; }).when(wal).appendBatchAndFsync(any());\n','')
s=replace(s,'''                if(group){verify(wal,times(1)).appendBatchAndFsync(any());verify(wal,never()).appendAndFsync(any());}
                else verify(wal, times(1)).appendAndFsync(any());''','''                verify(wal, times(1)).appendAndFsync(any());''')
changes[p]=s
p=tests+'pixel/websocket/PixelWebSocketEmbeddedTest.java';s=content(p)
s=replace(s,'groupCommandBroadcastReachesActualWebSocketAndHttpTileVersion','singleCommandBroadcastReachesActualWebSocketAndHttpTileVersion')
s=replace(s,'new dev.cgt.pixelplace.pixel.application.GroupPixelWriteExecutor(','new dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutor(')
s=replace(s,'server.readiness,new dev.cgt.pixelplace.pixel.application.WriteExecutionProperties(),measure))','server.readiness,measure))')
changes[p]=s
p=tests+'flush/infra/FlushPersistenceMySqlIntegrationTest.java';s=content(p)
s=cut(s,'    @ParameterizedTest\n    @ValueSource(strings = {"commit", "rollback", "lost-response", "cleanup-delay"})','    private Map<Path, byte[]> walBytes(')
changes[p]=s
p=tests+'measurement/PixelMeasurementTest.java';s=content(p)
s=replace(s,'measure.walStarted(true, 2); measure.walForced(2, true); measure.walCompleted(2);','measure.walSingleStarted(); measure.walForced(1, true); measure.walCompleted(1);\n        measure.walSingleStarted(); measure.walForced(1, true); measure.walCompleted(1);')
s=replace(s,'assertEquals(1, counts.recordForces());','assertEquals(2, counts.recordForces());')
s=replace(s,'assertEquals(1, counts.batchCalls()); assertEquals(2, counts.batchRecords());','assertEquals(2, counts.singleCalls()); assertEquals(0, counts.batchCalls()); assertEquals(0, counts.batchRecords());')
changes[p]=s
p=tests+'wal/infra/WalMeasurementTest.java';s=content(p)
s=replace(s,'AcrossBatchRotationAndSequenceGaps','AcrossSingleRotationAndSequenceGaps')
s=replace(s,'''            storage.appendBatchAndFsync(java.util.List.of(record(1), record(3), record(8)));
            storage.appendBatchAndFsync(java.util.List.of(record(10)));''','''            storage.appendAndFsync(record(1)); storage.appendAndFsync(record(3));
            storage.appendAndFsync(record(8)); storage.appendAndFsync(record(10));''')
s=replace(s,'java.util.List.of(java.util.List.of(1L, 3L), java.util.List.of(), java.util.List.of(8L), java.util.List.of(10L))','java.util.List.of(java.util.List.of(1L), java.util.List.of(3L), java.util.List.of(), java.util.List.of(8L), java.util.List.of(10L))')
s=replace(s,'assertNotEquals(files.get(0), files.get(1)); assertEquals(files.get(1), files.get(2)); assertEquals(files.get(2), files.get(3));','assertEquals(files.get(0), files.get(1)); assertNotEquals(files.get(1), files.get(2));\n            assertEquals(files.get(2), files.get(3)); assertEquals(files.get(3), files.get(4));')
s=replace(s,'assertEquals(2, c.batchCalls()); assertEquals(4, c.batchRecords());','assertEquals(4, c.singleCalls()); assertEquals(0, c.batchCalls()); assertEquals(0, c.batchRecords());')
s=replace(s,'assertEquals(3, c.recordForces());','assertEquals(4, c.recordForces());')
s=replace(s,'java.util.Map.of(1, 1L, 3, 1L)','java.util.Map.of()')
changes[p]=s
for name,value in changes.items():
    path=(root/name).resolve();assert path.is_relative_to(root.resolve()) and value.endswith('\n')
    path.write_text(value,encoding='utf-8')
print('single cleanup edited',len(changes),'files; frozen evidence retained; regression pending')
