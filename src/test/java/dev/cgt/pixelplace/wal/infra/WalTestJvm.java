package dev.cgt.pixelplace.wal.infra;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** test-owned JVM의 절대 JDK/classpath/temp·유한 IPC·종료 증거. 기존 프로세스에는 접근하지 않음 */
final class WalTestJvm implements AutoCloseable {
    final Process process;
    final Path evidence;
    final ExecutorService reader=Executors.newSingleThreadExecutor();
    final BufferedReader stdout;
    final List<String> lines=new ArrayList<>();
    final BufferedWriter stdin;

    WalTestJvm(Class<?> main, String... arguments) throws Exception {
        this(WalTestJvm::writeIdentity, main, arguments);
    }

    // 시작 후 증거 저장 실패도 이미 소유한 child의 정상 종료 책임을 없애지 못함
    WalTestJvm(IdentityWriter identityWriter, Class<?> main, String... arguments) throws Exception {
        evidence=Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),"phase17-child-");
        var classpath=new LinkedHashSet<String>();
        for(ClassLoader loader=main.getClassLoader();loader!=null;loader=loader.getParent()) {
            if(loader instanceof java.net.URLClassLoader urls)for(var url:urls.getURLs())classpath.add(Path.of(url.toURI()).toString());
        }
        if(classpath.isEmpty())classpath.addAll(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
        var command=new ArrayList<String>();
        command.add("-Xmx512m");
        command.add("-Djava.io.tmpdir="+evidence);command.add("-Djna.tmpdir="+evidence);
        command.add("-cp");command.add(String.join(File.pathSeparator,classpath));command.add(main.getName());
        command.addAll(List.of(arguments));
        Path argfile=evidence.resolve("args.txt");
        Files.write(argfile,command.stream().map(s->"\""+s.replace('\\','/')+"\"").toList());
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+argfile);
        builder.environment().put("TEMP",evidence.toString());builder.environment().put("TMP",evidence.toString());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        process=builder.redirectError(evidence.resolve("stderr.log").toFile()).start();
        stdout=process.inputReader();stdin=process.outputWriter();
        try { identityWriter.write(evidence, process); }
        catch (Exception | Error failure) {
            try { close(); } catch (Exception | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @FunctionalInterface
    interface IdentityWriter { void write(Path evidence, Process process) throws Exception; }

    private static void writeIdentity(Path evidence, Process process) throws IOException {
        Files.writeString(evidence.resolve("identity.txt"),"pid="+process.pid()+"\nstarted="+process.info().startInstant().orElseThrow()+"\njdk="+System.getProperty("java.home")+"\n");
    }
    String line() throws Exception {
        String line=reader.submit(stdout::readLine).get(30,TimeUnit.SECONDS);
        if(line==null)throw new AssertionError("Child ended before expected evidence: "+evidence);
        lines.add(line);return line;
    }
    String matching(String prefix) throws Exception {
        for(int n=0;n<64;n++){String line=line();if(line.startsWith(prefix))return line;}
        throw new AssertionError("Expected child marker absent");
    }
    void send(String command) throws IOException {stdin.write(command);stdin.newLine();stdin.flush();}
    void expectExit(int expected) throws Exception {
        if(!process.waitFor(30,TimeUnit.SECONDS))throw new AssertionError("Owned child timeout: "+evidence);
        String tail;while((tail=stdout.readLine())!=null)lines.add(tail);
        Files.write(evidence.resolve("stdout.log"),lines);
        Files.writeString(evidence.resolve("exit.txt"),Integer.toString(process.exitValue()));
        if(process.exitValue()!=expected)throw new AssertionError("Child exit="+process.exitValue()+" expected="+expected+" evidence="+evidence);
        System.out.println("CHILD_VERIFIED pid="+process.pid()+" exit="+expected+" evidence="+evidence);
    }
    @Override public void close() throws Exception {
        Throwable failure = null;
        try {
            if(process.isAlive()) {
                // 먼저 종료한 child의 닫힌 pipe는 실제 exit 대기로 확인
                try{send("close");}catch(IOException ignored){ }
            }
            awaitNormalExit(process, evidence, 10);
        } catch (Exception | Error problem) { failure = problem; }
        // child 종료 후 pipe EOF로 reader를 소진. interrupt로 native child/I/O를 취소하지 않음
        boolean interrupted = Thread.interrupted();
        reader.shutdown();
        try {
            if(!reader.awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("IPC reader remains alive: " + evidence);
        } catch (Exception | Error problem) {
            interrupted |= problem instanceof InterruptedException;
            failure = combine(failure, problem);
        }
        try { stdout.close(); } catch (Exception | Error problem) { failure = combine(failure, problem); }
        try { stdin.close(); } catch (Exception | Error problem) { failure = combine(failure, problem); }
        if (interrupted) Thread.currentThread().interrupt();
        rethrow(failure);
    }

    // 관측 기한 초과는 실패로 고정. 늦은 exit0도 덮어쓰지 않으며 정상 종료까지 소유 유지
    static void awaitNormalExit(Process process, Path evidence, long seconds) throws Exception {
        boolean interrupted = false;
        Throwable failure;
        try {
            try {
                if (process.waitFor(seconds, TimeUnit.SECONDS)) return;
                failure = new AssertionError("Owned child close observation timed out: " + evidence);
            } catch (InterruptedException problem) {
                interrupted = true;
                failure = problem;
            }
            try {
                Files.writeString(evidence.resolve("close-incomplete.txt"), "pid=" + process.pid()
                        + "\nalive=" + process.isAlive() + "\nfailure=" + failure.getClass().getName()
                        + "\nnormalExitPending=true\n");
            } catch (Exception | Error problem) { failure = combine(failure, problem); }
            for (;;) {
                try { process.waitFor(); break; }
                catch (InterruptedException problem) { interrupted = true; failure = combine(failure, problem); }
            }
            try {
                Files.writeString(evidence.resolve("close-exit.txt"), "pid=" + process.pid()
                        + "\nexit=" + process.exitValue() + "\nobservationFailed=true\n");
            } catch (Exception | Error problem) { failure = combine(failure, problem); }
            rethrow(failure);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static Throwable combine(Throwable first, Throwable later) {
        if (first == null) return later;
        if (first != later) first.addSuppressed(later);
        return first;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
    }
}
