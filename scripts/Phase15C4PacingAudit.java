import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

/** DB·WAL·HTTP 없이 동일 예정 간격의 대기 손실을 분리하는 진단. 실제 서버 성능 증거 아님 */
class Phase15C4PacingAudit {
    public static void main(String[] args) throws Exception {
        for (boolean boundedSpin : new boolean[]{false,true,true,false}) run(boundedSpin,8);
    }

    private static void run(boolean boundedSpin,int seconds) throws Exception {
        long start=System.nanoTime(),end=start+seconds*1_000_000_000L;
        long interval=(long)(1e9/105);int planned=seconds*105,sent=0,late=0;
        long[] delays=new long[planned];
        var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        long cpu=os.getProcessCpuTime();
        for(int i=0;i<planned;i++) {
            long due=start+(long)(i*1e9/105);
            while(System.nanoTime()<due) {
                long remaining=due-System.nanoTime();
                if(boundedSpin) {
                    // 예정 간격보다 큰 wake-up 지연을 마지막 유한 구간에서 피하는 후보
                    if(remaining>20_000_000L)LockSupport.parkNanos(remaining-20_000_000L);
                    else Thread.onSpinWait();
                } else if(remaining>200_000L)LockSupport.parkNanos(remaining-100_000L);
                else Thread.onSpinWait();
                if(Thread.interrupted())throw new InterruptedException();
            }
            long now=System.nanoTime();
            if(now>=end || now-due>=interval)late++;
            else delays[sent++]=now-due;
        }
        long elapsed=System.nanoTime()-start;
        Arrays.sort(delays,0,sent);
        System.out.printf(java.util.Locale.ROOT,
            "{\"pacing\":\"%s\",\"seconds\":%d,\"planned\":%d,\"eligible\":%d,\"arrivalLate\":%d,\"eligiblePerSecond\":%.6f,\"delayP99Millis\":%.6f,\"cpuCores\":%.6f}%n",
            boundedSpin?"bounded-spin-candidate":"frozen-candidate-wait",seconds,planned,sent,late,(double)sent/seconds,
            delays[(int)Math.ceil(sent*.99)-1]/1e6,(double)(os.getProcessCpuTime()-cpu)/elapsed);
    }
}
