package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 상위 수신·실제 Process 시작 경합·기한 이후 적용 관측 검증. production 3 JVM 증거는 별도 fixture */
class Phase18ParentControlTest {
    @TempDir Path directory;

    private Phase18Process.Command command(String mode,long budget)throws Exception {
        Path args=directory.resolve(mode+".args");
        var original=Phase18Tools.command(Path.of(System.getProperty("java.home"),"bin/java.exe"),args,directory.resolve(mode),mode,2000,65536);
        Files.writeString(args,Files.readString(args).replace(Phase18FixtureChild.class.getName(),Phase18StopFixture.class.getName())
                .replace("\""+mode+"\"","\"protocol\"\n\""+mode+"\""));
        return new Phase18Process.Command(original.arguments(),original.cwd(),original.evidence(),budget,2000,65536);
    }

    @Test void parentCloseBeforeStartPreventsProcessAndAnyDispatch()throws Exception {
        var parent=new Phase18ParentControl(1000,2000);
        parent.listen(new ByteArrayInputStream("CLOSE\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!parent.stopped()&&System.nanoTime()<until)Thread.sleep(1);
        assertTrue(parent.stopped());var cmd=command("before",3000);
        assertThrows(IOException.class,()->Phase18Process.run(cmd,child->fail("Body after upstream stop"),BenchmarkJson::write,Map.of(),parent));
        assertFalse(Files.exists(cmd.evidence().resolve("identity.json")));
        assertThrows(IOException.class,()->parent.handoff(()->{fail("New HTTP after stop");return null;}));
        assertTrue(Phase18Process.unresolved().isEmpty());
    }

    @Test void expiredExecutionStillObservesActualAppliedDrainAndExitWithoutRestoringSuccess()throws Exception {
        var parent=new Phase18ParentControl(1000,2000);var cmd=command("expired",600);
        var failure=assertThrows(TimeoutException.class,()->Phase18Process.run(cmd,child->{
            assertEquals("READY",parent.receive(child,3000));Thread.sleep(650);parent.receive(child,1000);
        },BenchmarkJson::write,Map.of(),parent));
        assertEquals("Control observation deadline",failure.getMessage());
        var report=parent.report();assertEquals(true,report.get("timelyApplied"));assertEquals(true,report.get("callbacksDrained"));
        assertEquals(true,report.get("rawSaved"));assertEquals(false,report.get("producerAlive"));
        assertEquals(0,BenchmarkJson.read(cmd.evidence().resolve("exit.json")).path("exit").asInt(-1));
        assertFalse(Files.exists(cmd.evidence().resolve("manifest.json")));assertTrue(Phase18Process.unresolved().isEmpty());
        assertEquals(List.of("READY","APPLIED 0 "),((List<?>)report.get("observed")).subList(0,2).stream().map(Object::toString).map(s->s.startsWith("APPLIED")?"APPLIED 0 ":s).toList());
    }

    @Test void stopDuringActualProcessHandoffOwnsAndStopsChildWithoutEnteringBody()throws Exception {
        var parent=new Phase18ParentControl(1000,2000);var cmd=command("race",5000);
        var identity=new CountDownLatch(1);var release=new CountDownLatch(1);
        var original=new Phase18ParentControl.Stopped("race");
        try(var pool=Executors.newFixedThreadPool(2)){
            var run=pool.submit(()->assertThrows(IOException.class,()->Phase18Process.run(cmd,child->fail("Body after overlapping stop"),(path,value)->{
                if(path.getFileName().toString().equals("identity.json")){identity.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));}
                BenchmarkJson.write(path,value);
            },Map.of(),parent)));
            assertTrue(identity.await(3,TimeUnit.SECONDS));
            var stop=pool.submit(()->parent.stop("CLOSE",original));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!parent.stopped()&&System.nanoTime()<until)Thread.sleep(1);
            assertTrue(parent.stopped());assertFalse(stop.isDone());release.countDown();
            stop.get(3,TimeUnit.SECONDS);assertSame(original,run.get(5,TimeUnit.SECONDS));
        }
        assertTrue(Files.exists(cmd.evidence().resolve("identity.json")));
        assertEquals("CLOSE",parent.report().get("notification"));
        assertEquals(true,parent.report().get("stopSendSucceeded"));assertEquals(true,parent.report().get("timelyApplied"));
        assertEquals(0L,parent.report().get("appliedDispatches"));assertEquals(false,parent.report().get("producerAlive"));
        assertTrue(Phase18Process.unresolved().isEmpty());assertFalse(Files.exists(cmd.evidence().resolve("manifest.json")));
    }

    @Test void missingAndLateAppliedRemainUnconfirmedDespiteDoneAndExitZero()throws Exception {
        for(String mode:List.of("missing","late")){
            var parent=new Phase18ParentControl(50,2000);var cmd=command(mode,5000);
            var first=new Phase18ParentControl.Stopped("test parent CLOSE");
            assertSame(first,assertThrows(IOException.class,()->Phase18Process.run(cmd,child->{
                assertEquals("READY",parent.receive(child,3000));parent.stop("CLOSE",first);parent.check();
            },BenchmarkJson::write,Map.of(),parent)));
            assertEquals(false,parent.report().get("timelyApplied"));assertEquals(true,parent.report().get("rawSaved"));
            assertFalse(((Map<?,?>)parent.report().get("unconfirmedAtDeadline")).isEmpty());
            assertTrue(Phase18ParentControl.failures(first).stream().anyMatch(s->s.contains("unconfirmed")));
            String stdout=Files.readString(cmd.evidence().resolve("stdout.log"));
            assertEquals(mode.equals("late"),stdout.contains("APPLIED"));assertTrue(stdout.contains("DRAINED 0"));assertTrue(stdout.contains("DONE 0"));
            assertEquals(0,BenchmarkJson.read(cmd.evidence().resolve("exit.json")).path("exit").asInt(-1));
            assertFalse(Files.exists(cmd.evidence().resolve("manifest.json")));assertTrue(Phase18Process.unresolved().isEmpty());
        }
    }
}
