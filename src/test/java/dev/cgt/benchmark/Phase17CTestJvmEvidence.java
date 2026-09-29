package dev.cgt.benchmark;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import java.lang.management.ManagementFactory;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** C가 명시 활성화한 실제 test JVM에서만 provenance 저장. 테스트 판정/조건/대상 변경 없음 */
public final class Phase17CTestJvmEvidence implements BeforeAllCallback {
    private static final AtomicBoolean RECORDED=new AtomicBoolean();
    @Override public void beforeAll(ExtensionContext ignored)throws Exception {
        String output=System.getProperty("pixelplace.phase17c.evidence");
        if(output==null||!RECORDED.compareAndSet(false,true))return;
        Path directory=Phase17CPlan.absolute(output);BenchmarkGuards.rejectLinks(directory);
        var classpath=new TreeSet<String>();
        classpath.addAll(Arrays.asList(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        for(ClassLoader loader=Thread.currentThread().getContextClassLoader();loader!=null;loader=loader.getParent())if(loader instanceof URLClassLoader urls)
            for(var url:urls.getURLs())if(url.getProtocol().equals("file"))classpath.add(Path.of(url.toURI()).toString());
        var hashes=new TreeMap<String,String>();
        for(String entry:classpath){Path p=Path.of(entry);if(Files.isRegularFile(p))hashes.put(p.toString(),BenchmarkJson.hash(p));else if(Files.isDirectory(p))try(var files=Files.walk(p)){for(Path f:files.filter(Files::isRegularFile).sorted().toList())hashes.put(f.toString(),BenchmarkJson.hash(f));}}
        if(hashes.isEmpty())throw new IllegalStateException("Actual test classpath not observed");
        Path java=Path.of(System.getProperty("java.home"),"bin/java.exe");
        var result=new LinkedHashMap<String,Object>();result.put("pid",ProcessHandle.current().pid());result.put("start",ProcessHandle.current().info().startInstant().orElseThrow().toString());result.put("observedUtc",Instant.now().toString());result.put("java",java.toString());result.put("javaHash",BenchmarkJson.hash(java));result.put("jdk",System.getProperty("java.runtime.version"));result.put("user",System.getProperty("user.name"));result.put("temp",System.getProperty("java.io.tmpdir"));result.put("jnaTemp",System.getProperty("jna.tmpdir"));result.put("arguments",ManagementFactory.getRuntimeMXBean().getInputArguments());result.put("classpath",classpath);result.put("hashes",hashes);
        BenchmarkJson.write(directory.resolve("test-jvm-"+ProcessHandle.current().pid()+".json"),result);
    }
}
