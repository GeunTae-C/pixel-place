package dev.cgt.benchmark;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class Phase18K6CausalTest {
    private Path output(String name){return Path.of("F:/pixel-place-work/evidence",System.getenv("PIXEL_PLACE_WORK_SESSION"),name);}
    @Test void subMillisBoundsRoundOutwardAndPersistLastConnection()throws Exception{
        var now=new AtomicLong(100);var path=output("causal-selected.bin");var c=new Phase18K6Causal(2,100,path,now::get);
        now.set(100+1234567);assertEquals(0,c.millis(false));assertEquals(3,c.millis(true));
        c.received(99,1,1,2);c.close();assertEquals(1600,Files.size(path));
        try(var in=new RandomAccessFile(path.toFile(),"r")){in.seek(1592);assertEquals(1,in.readInt());assertEquals(2,in.readInt());}
    }
    @Test void backwardMonotonicClockAndUnboundedLifetimeFail(){
        var now=new AtomicLong(100);var c=new Phase18K6Causal(1,1,output("causal-rejected.bin"),now::get);
        now.set(99);assertThrows(IllegalArgumentException.class,()->c.millis(false));
        now.set(21600000000100L);assertThrows(IllegalArgumentException.class,()->c.millis(true));
        assertThrows(IllegalArgumentException.class,()->c.received(0,0,10,9));
        assertThrows(IllegalArgumentException.class,()->new Phase18K6Causal(320001,100,output("not-created.bin")));
    }
    @Test void duplicateCannotOverwriteRetainedBound(){
        var c=new Phase18K6Causal(1,1,output("causal-duplicate.bin"));c.received(0,0,1,2);
        assertThrows(IllegalArgumentException.class,()->c.received(0,0,1,3));
    }
}
