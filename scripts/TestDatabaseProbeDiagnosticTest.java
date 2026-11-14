import java.nio.file.*;
import java.time.Instant;
import java.lang.reflect.Proxy;
import java.util.Optional;

/** DB 없이 실제 PID와 제한된 ProcessHandle 대역으로 진단 단계·identity 거부 유지 확인 */
class TestDatabaseProbeDiagnosticTest {
    static int checks;
    interface Work { void run() throws Exception; }
    static void fails(String expected, Work work) throws Exception {
        boolean failed = false;
        try { work.run(); } catch (Exception failure) { failed = true; }
        if (!failed || !expected.equals(TestDatabaseProbe.stage))
            throw new AssertionError(expected + ": " + TestDatabaseProbe.stage);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);Files.createDirectory(root);
        ProcessHandle self = ProcessHandle.current();
        String pid = Long.toString(self.pid());String start = self.info().startInstant().orElseThrow().toString();
        Files.writeString(root.resolve("mysql.pid"), pid);
        for (String phase : new String[]{"before_db", "after_db"}) {
            TestDatabaseProbe.identity(root, pid, start, phase);checks++;
            fails(phase+"_pid_lookup", () -> TestDatabaseProbe.identity(root, Long.toString(Long.MAX_VALUE), start, phase));
            fails(phase+"_start_match", () -> TestDatabaseProbe.identity(root, pid, Instant.EPOCH.toString(), phase));
            ProcessHandle.Info missing = (ProcessHandle.Info) Proxy.newProxyInstance(ProcessHandle.Info.class.getClassLoader(),new Class[]{ProcessHandle.Info.class},(p,m,a)->Optional.empty());
            ProcessHandle fake = (ProcessHandle) Proxy.newProxyInstance(ProcessHandle.class.getClassLoader(),new Class[]{ProcessHandle.class},(p,m,a)->switch(m.getName()){case "isAlive"->true;case "info"->missing;default->throw new UnsupportedOperationException();});
            fails(phase+"_start_lookup", () -> TestDatabaseProbe.checkIdentity(root, self.pid(), start, phase, fake));
        }
        Files.writeString(root.resolve("mysql.pid"), "0");
        fails("before_db_pid_file", () -> TestDatabaseProbe.identity(root,pid,start,"before_db"));
        System.out.println("PROBE_DIAGNOSTICS_PASS="+checks);
    }
}
