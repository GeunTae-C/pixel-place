package dev.cgt.benchmark;

import com.sun.jna.*;
import java.util.*;

/** 현재 Windows 프로세스 전체 I/O 누적값. WAL/DB 단독 비용으로 해석 금지 */
final class Phase18ProcessIo {
    interface Kernel extends com.sun.jna.win32.StdCallLibrary {
        Pointer GetCurrentProcess();
        boolean GetProcessIoCounters(Pointer process, Counters counters);
    }
    @Structure.FieldOrder({"readOperations","writeOperations","otherOperations","readBytes","writeBytes","otherBytes"})
    public static class Counters extends Structure {
        public long readOperations,writeOperations,otherOperations,readBytes,writeBytes,otherBytes;
    }
    private static Kernel kernel;
    static synchronized Map<String,Object> sample() {
        try {
            if(kernel==null)kernel=Native.load("kernel32",Kernel.class);
            var value=new Counters();if(!kernel.GetProcessIoCounters(kernel.GetCurrentProcess(),value))return Map.of("status","missing","reason","GetProcessIoCounters");
            return Map.of("status","observed","readOperations",value.readOperations,"writeOperations",value.writeOperations,"otherOperations",value.otherOperations,
                    "readBytes",value.readBytes,"writeBytes",value.writeBytes,"otherBytes",value.otherBytes,"scope","whole process; includes network and evidence I/O");
        } catch(RuntimeException|LinkageError unavailable){return Map.of("status","missing","reason",unavailable.getClass().getSimpleName());}
    }
}
