package dev.cgt.benchmark;

/** raw v1의 실제 송신/최초 HTTP 결과. 늦은 WS/DB 관측은 별도 resolution 증거이며 terminal 덮어쓰기 금지 */
record Phase18Raw(int schemaVersion, String planHash, String runId, String caseId, String requestId,
        String kind, String scheduledPhase, long ordinal, String clockId, long scheduledOffsetNanos,
        long decidedNanos, Long sentNanos, Long completedNanos, String status, String reason,
        Integer httpStatus, Long eventSeq, Long tileVersion, String sendState, String outcome,
        String resolution, Integer userOrdinal, Integer userAttempt, int x, int y, int color,
        String rawSha256, Integer wireBytes, Long validationNanos) {
    static String clock() { return ProcessHandle.current().pid() + "/" + ProcessHandle.current().info().startInstant().orElseThrow(); }
}
