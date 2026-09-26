package dev.cgt.benchmark;

/** 불확실 요청의 사용자를 격리하고 완료 후 180초+여유 전 재사용을 막는 발생기 소유 pool */
public final class BenchmarkUsers {
    public record Assignment(int ordinal, int attemptOrdinal) { }
    private final long[] availableAt;
    private final int[] attempts;
    private final boolean[] busy, retired;
    private final boolean once;
    private final long reuseNanos;
    private int cursor;

    public BenchmarkUsers(int users, boolean once, long marginMillis) {
        availableAt = new long[users]; attempts = new int[users]; busy = new boolean[users]; retired = new boolean[users];
        this.once = once; reuseNanos = Math.multiplyExact(Math.addExact(180_000, marginMillis), 1_000_000);
        // ordinal 0은 bootstrap 소유. steady 부하에서 재사용 금지
        retired[0] = true;
    }
    public synchronized Assignment acquire(long now) {
        for (int i = 0; i < busy.length; i++) {
            int ordinal = cursor++ % busy.length;
            if (cursor == busy.length) cursor = 0;
            if (!busy[ordinal] && !retired[ordinal] && (attempts[ordinal] == 0 || now >= availableAt[ordinal])) {
                busy[ordinal] = true;
                return new Assignment(ordinal, ++attempts[ordinal]);
            }
        }
        return null;
    }
    public synchronized void complete(Assignment user, long now, boolean uncertain) {
        BenchmarkSpec.require(busy[user.ordinal], "pool completion");
        busy[user.ordinal] = false;
        retired[user.ordinal] = once || uncertain;
        availableAt[user.ordinal] = Math.addExact(now, reuseNanos);
    }
}
