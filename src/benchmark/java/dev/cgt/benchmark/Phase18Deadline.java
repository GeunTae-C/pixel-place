package dev.cgt.benchmark;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** runner가 한 번 정한 UTC 만료를 JVM별 monotonic 잔여 기한으로 제한. 자식/복구에서 예산 재부여 금지 */
final class Phase18Deadline {
    static final String PROPERTY = "phase18.deadlineEpochMillis";
    private final long expiresEpochMillis, deadlineNanos, limitMillis;

    private Phase18Deadline(long expires, long remaining, long limit) {
        expiresEpochMillis=expires;deadlineNanos=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(remaining);limitMillis=limit;
    }
    /** 검증된 단일 trial만 장기 프로세스 예산 사용 가능. 기존 fixture scope도 같은 gate로 확인 */
    static Phase18Deadline start(Phase18Plan plan,long millis) throws Exception {
        plan.validate();Phase18Execution.requireService(plan);
        long limit=Math.multiplyExact(plan.bounds().totalSeconds(),1000);
        Phase18Plan.require(millis>0&&millis<=limit,"execution deadline within validated plan");
        return new Phase18Deadline(Math.addExact(System.currentTimeMillis(),millis),millis,limit);
    }
    static Phase18Deadline inherit(Phase18Plan plan) throws Exception {
        return inherit(plan,System.getProperty(PROPERTY,""));
    }
    /** wall clock은 JVM 사이의 만료 전달용. 한 JVM 안에서는 시계 후퇴로 잔여 실행 시간이 늘어나지 않음 */
    static Phase18Deadline inherit(Phase18Plan plan,String value) throws Exception {
        plan.validate();Phase18Execution.requireService(plan);
        Phase18Plan.require(value.matches("[1-9][0-9]{0,18}"),"inherited execution deadline required");
        long expires=Long.parseLong(value),remaining=Math.subtractExact(expires,System.currentTimeMillis());
        long limit=Math.multiplyExact(plan.bounds().totalSeconds(),1000);
        Phase18Plan.require(remaining>0&&remaining<=limit,"inherited deadline exhausted or beyond plan");
        return new Phase18Deadline(expires,remaining,limit);
    }
    long expiresEpochMillis(){return expiresEpochMillis;}
    long deadlineNanos(){return deadlineNanos;}
    long limitMillis(){return limitMillis;}
    void check() throws TimeoutException {
        if(System.nanoTime()>=deadlineNanos||System.currentTimeMillis()>=expiresEpochMillis)
            throw new TimeoutException("Cumulative execution deadline");
    }
    /** 정상 단계 대기만 전체 기한에 제한. 실패 후 정리·STOP ACK 관측에는 사용하지 않음 */
    long capMillis(long millis) throws TimeoutException {
        check();
        long remaining=Math.min(TimeUnit.NANOSECONDS.toMillis(deadlineNanos-System.nanoTime()),expiresEpochMillis-System.currentTimeMillis());
        if(remaining<=0)throw new TimeoutException("Cumulative execution deadline");
        return Math.min(millis,remaining);
    }
}
