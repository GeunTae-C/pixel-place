package dev.cgt.benchmark;

import java.util.*;

/** 송신 cohort와 완료창을 분리하고 raw nearest-rank를 계산하는 순수 집계 */
public final class BenchmarkResults {
    private BenchmarkResults() { }
    public enum Outcome { accepted, policy_4xx, server_5xx, timeout, network_error, malformed_200, unexpected_status, client_not_sent }
    public record Attempt(long requestId, Integer fixtureUserOrdinal, Integer userAttemptOrdinal, String phase,
                          long scheduledNanos, Long sentNanos, long completedNanos, Outcome outcome,
                          Integer httpStatus, Long eventSeq, Long tileVersion, String reason) {
        public boolean unknown() { return Set.of(Outcome.server_5xx, Outcome.timeout, Outcome.network_error, Outcome.malformed_200, Outcome.unexpected_status).contains(outcome); }
    }
    public record Percentiles(int samples, Double p50Millis, Double p95Millis, Double p99Millis, Double maxMillis) { }
    public record Summary(int planned, int sent, int cohortAccepted, int completedAccepted, double acceptedPerSecond,
                          Double systemErrorRate, double notSentRate, Map<Outcome, Long> outcomes,
                          Percentiles successRtt, Percentiles scheduledToResponse, Percentiles sendDelay,
                          int drainAccepted, int unknown, long windowStartNanos, long windowEndNanos) { }

    public static Summary aggregate(List<Attempt> attempts, long start, long end) {
        BenchmarkSpec.require(end > start, "measurement window");
        Set<Long> ids = new HashSet<>();
        Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
        List<Long> rtt = new ArrayList<>(), scheduled = new ArrayList<>(), delay = new ArrayList<>();
        int sent = 0, planned = 0, cohortAccepted = 0, completed = 0, errors = 0, notSent = 0, drained = 0, unknown = 0;
        for (Attempt a : attempts) {
            BenchmarkSpec.require(ids.add(a.requestId), "duplicate terminal outcome");
            BenchmarkSpec.require(a.outcome != null && a.completedNanos >= a.scheduledNanos, "attempt timing");
            if (a.outcome == Outcome.accepted) {
                BenchmarkSpec.require(a.sentNanos != null && a.eventSeq != null && a.eventSeq > 0 && a.tileVersion != null && a.tileVersion > 0 && Objects.equals(a.httpStatus, 200), "accepted contract");
                if (a.completedNanos >= start && a.completedNanos < end) completed++;
            }
            boolean plannedHere = a.scheduledNanos >= start && a.scheduledNanos < end;
            if (plannedHere) planned++;
            if (a.outcome == Outcome.client_not_sent) {
                BenchmarkSpec.require(a.sentNanos == null, "not_sent timing");
                if (plannedHere) { notSent++; counts.merge(a.outcome, 1L, Long::sum); }
                continue;
            }
            BenchmarkSpec.require(a.sentNanos != null && a.completedNanos >= a.sentNanos, "sent timing");
            if (a.sentNanos < start || a.sentNanos >= end) continue;
            sent++; counts.merge(a.outcome, 1L, Long::sum);
            delay.add(a.sentNanos - a.scheduledNanos);
            if (a.unknown()) unknown++;
            if (Set.of(Outcome.server_5xx, Outcome.timeout, Outcome.network_error, Outcome.malformed_200).contains(a.outcome)) errors++;
            if (a.outcome == Outcome.accepted) {
                cohortAccepted++;
                rtt.add(a.completedNanos - a.sentNanos);
                scheduled.add(a.completedNanos - a.scheduledNanos);
                if (a.completedNanos >= end) drained++;
            }
        }
        return new Summary(planned, sent, cohortAccepted, completed, completed / ((end - start) / 1e9),
                sent == 0 ? null : (double) errors / sent, planned == 0 ? 0 : (double) notSent / planned,
                Map.copyOf(counts), percentiles(rtt), percentiles(scheduled), percentiles(delay), drained, unknown, start, end);
    }

    public static Percentiles percentiles(List<Long> nanos) {
        if (nanos.isEmpty()) return new Percentiles(0, null, null, null, null);
        long[] ordered = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        return new Percentiles(ordered.length, rank(ordered, .5), rank(ordered, .95), rank(ordered, .99), ordered[ordered.length - 1] / 1e6);
    }
    private static double rank(long[] values, double fraction) { return values[(int) Math.ceil(values.length * fraction) - 1] / 1e6; }

    /** request ordinal로 좌표·색 재생성. 사용자 배정 순서는 별도의 fixture ordinal로 대조 */
    public static int[] pixel(BenchmarkSpec.Case c, long seed, long requestId) {
        long mixed = requestId * 0x9E3779B97F4A7C15L + seed;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return "same-pixel".equals(c.pattern()) ? new int[]{17, 19, (int) (requestId & 255)}
                : new int[]{(int) (mixed & 8191), (int) ((mixed >>> 13) & 8191), (int) (requestId & 255)};
    }
}
