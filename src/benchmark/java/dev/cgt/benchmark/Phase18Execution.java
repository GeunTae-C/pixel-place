package dev.cgt.benchmark;

import java.util.Set;

/** 진단·공식 PILOT·후속 단일 trial의 실행 경계. action 선택만으로 다른 단계나 반복을 시작하지 않음 */
final class Phase18Execution {
    static final long COOLDOWN_MILLIS=5000;
    private Phase18Execution() { }
    static boolean observed(Phase18Plan p) { return !p.stage().equals("A-2"); }
    static boolean fixture(Phase18Plan p) { return Set.of("A-2", "A-3").contains(p.stage()); }

    static void requireAction(String action, Phase18Plan p) {
        String expected = switch (p.stage()) {
            case "A-2" -> "VerifyTransport";
            case "A-3" -> "VerifyObservation";
            case "A-4" -> "Pilot";
            case "B", "C", "D" -> "Run";
            default -> "unavailable";
        };
        Phase18Plan.require(action.equals(expected), "action/stage");
        requireService(p);
    }

    /** 모든 app/runner 진입점에서 같은 scope 검사. 미래 실행은 fresh ready plan 하나로만 허용 */
    static void requireService(Phase18Plan p) {
        Phase18Plan.require(p.state().equals("ready") && p.cases().size() == 1, "ready single trial");
        var c = p.cases().getFirst(); var w = c.workload(); var n = p.counts(c);
        Phase18Plan.require(fixture(p) || !c.caseId().startsWith("stop") && !Set.of("timeout", "upstream-timeout", "upstream-before").contains(c.caseId()),
                "diagnostic injection identifiers are fixture-only");
        Phase18Plan.require(Set.of("write", "read", "mixed", "soak").contains(c.kind())
                && !p.ownership().wal().isBlank() && !p.ownership().catalog().isBlank()
                && p.ownership().redisIndex() >= 2, "service workload/ownership");
        if (fixture(p)) {
            Phase18Plan.require(p.phase().equals("A") && c.role().equals("diagnostic")
                    && n.writes() <= 64 && n.reads() <= 64 && w.wsConnections() <= 4
                    && w.measurementSeconds() <= 10 && w.warmupSeconds() <= 5
                    && c.runtime().segmentBytes() == 33554432 && p.bounds().totalSeconds() <= 600,
                    "small A-2/A-3 fixture only");
        } else if (p.stage().equals("A-4")) {
            Phase18Plan.require(p.phase().equals("A") && c.role().equals("pilot") && c.kind().equals("mixed")
                    && n.writes() <= 64 && n.reads() <= 64 && w.wsConnections() > 0 && w.wsConnections() <= 4
                    && w.measurementSeconds() <= 10 && w.warmupSeconds() <= 5 && w.bootstrap()
                    && c.runtime().mode().equals("group") && c.runtime().measurement()
                    && c.runtime().segmentBytes() == 33554432 && p.bounds().totalSeconds() <= 600,
                    "limited official PILOT");
        } else {
            Phase18Plan.require(Set.of("B", "C", "D").contains(p.stage()) && p.phase().equals(p.stage())
                    && Set.of("target", "diagnostic", "soak").contains(c.role()), "subsequent finite trial");
        }
    }

    /** 계획상 HTTP와 별도 준비 진단4건만 보유. 고정160건으로 대표·SOAK를 거부하지 않음 */
    static int requestCapacity(Phase18Plan p) {
        var n = p.counts(p.cases().getFirst());
        return Math.toIntExact(Math.addExact(4, Math.addExact(n.writes(), n.reads())));
    }

    /** 측정 기간은 응답 지연 기한과 별개. STOP 적용 기한은 ParentControl에서 독립 확인 */
    static long phaseWaitMillis(long seconds, long controlMillis) {
        return Math.addExact(Math.multiplyExact(seconds, 1000), controlMillis);
    }
    /** 앱 수신 대기와 발생기 선행 작업의 연결점 */
    static long responseWaitMillis(String message,Phase18Plan.Workload w) {
        return switch(message) {
            case "WARMUP_DONE" -> Math.addExact(phaseWaitMillis(w.warmupSeconds(),w.controlMillis()),w.drainMillis());
            case "SEND_DONE" -> phaseWaitMillis(w.measurementSeconds(),w.controlMillis());
            case "BOOTSTRAP" -> Math.addExact(w.readyMillis(),Math.addExact(w.drainMillis(),w.controlMillis()));
            case "READY" -> Math.addExact(Math.multiplyExact(w.bootstrap()?1:2,w.readyMillis()),w.controlMillis());
            case "CONNECT" -> Math.addExact(w.controlMillis(),Math.addExact(w.drainMillis(),COOLDOWN_MILLIS));
            case "MEASURE" -> Math.addExact(w.controlMillis(),w.drainMillis());
            // callback → WS 수신 → 전체 socket close → 최종 집합 확인의 네 소진 구간
            case "DRAINED" -> Math.addExact(Math.multiplyExact(4,w.drainMillis()),w.controlMillis());
            case "DONE" -> w.drainMillis();
            default -> w.controlMillis();
        };
    }
}
