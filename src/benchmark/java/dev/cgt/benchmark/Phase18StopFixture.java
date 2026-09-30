package dev.cgt.benchmark;

import java.io.*;
import java.nio.file.*;

/** A2-MF-01의 제한 음성 실행 진입점. 실제 Transport/앱/발생기 경로와 단위 제어 자식만 제공 */
public final class Phase18StopFixture {
    private Phase18StopFixture() { }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("protocol")) { protocol(args[1]); return; }
        if (args.length != 4 || !args[0].equals("transport")) throw new IllegalArgumentException("transport plan environment initial-budget-millis");
        var planFile=Phase18Paths.checked(args[1]);var environment=Phase18Paths.checked(args[2]);
        var loaded=Phase18Plan.read(planFile);Phase18Runtime.verify(loaded.plan());
        boolean before=loaded.plan().cases().getFirst().caseId().equals("upstream-before");
        Phase18Plan.require(before||loaded.plan().cases().getFirst().caseId().equals("upstream-timeout"),"upstream negative fixture only");
        Phase18Transport.run(loaded,planFile,environment,Long.parseLong(args[3]),child->{
            if(before){
                Phase18Plan.require(child.receive(90000).equals("APP_PREPARING"),"actual app ready entry");
                child.send("CLOSE");throw new IOException("Intentional upstream stop before producer");
            }
        });
        throw new AssertionError("Negative transport unexpectedly succeeded");
    }
    /** 실제 JVM 인계 경합/기한 후 ACK 검증용. HTTP/production 실행 증거와 구분 */
    private static void protocol(String mode)throws Exception {
        var reader=new BufferedReader(new InputStreamReader(System.in));
        System.out.println("READY");System.out.flush();
        String message;
        while((message=reader.readLine())!=null){
            if(message.equals("STOP")||message.equals("CLOSE")){
                long applied=System.nanoTime();
                if(mode.equals("late"))Thread.sleep(300);
                if(!mode.equals("missing"))System.out.println("APPLIED 0 "+applied+" "+applied);
                System.out.println("DRAINED 0");System.out.println("DONE 0");return;
            }
            throw new AssertionError("Unexpected dispatch control");
        }
        throw new EOFException("Parent lost before STOP");
    }
}
