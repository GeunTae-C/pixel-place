package dev.cgt.benchmark;

import java.nio.file.*;
import java.io.*;
import java.util.function.LongSupplier;

/** 동일 bridge 단조 시계의 인과 경계. callback epoch를 대체하거나 시계 offset을 추정하지 않음. */
final class Phase18K6Causal {
    private final LongSupplier clock;
    private final long origin;
    private byte[][] bounds;
    private final Path output;
    private long frequency;
    Phase18K6Causal(int events,int connections,Path output){
        this(events,connections,output,System::nanoTime);
        Phase18Plan.require(System.getProperty("os.name").startsWith("Windows"),"causal Windows clock support");
        var value=new com.sun.jna.ptr.LongByReference();
        int ok=com.sun.jna.NativeLibrary.getInstance("kernel32").getFunction("QueryPerformanceFrequency").invokeInt(new Object[]{value});
        frequency=value.getValue();Phase18Plan.require(ok!=0&&frequency>=10000,"causal QPC resolution support");
    }
    java.util.Map<String,Object> evidence(){return java.util.Map.of("kind","bridge-causal-brackets-v1","clockId",Phase18Raw.clock(),"source","Windows HotSpot System.nanoTime/QPC","qpcFrequency",frequency,"outwardGuardMillis",1,"maximumElapsedMillis",21600000,"epochOffsetUsed",false);}

    Phase18K6Causal(int events,int connections,Path output,LongSupplier clock){
        Phase18Plan.require(events>=0&&events<=Phase18Cap1.MAX_EVENTS&&connections>=0&&connections<=100,"causal bounded allocation");
        Phase18Plan.require(Math.multiplyExact(Math.multiplyExact((long)events,connections),7)<=Phase18Cap1.TIMING_MEMORY,"causal array bytes");
        this.clock=clock;origin=clock.getAsLong();this.output=output;bounds=new byte[connections][Math.multiplyExact(events,7)];
    }
    int millis(boolean upper){
        long elapsed=clock.getAsLong()-origin;
        Phase18Plan.require(elapsed>=0&&elapsed<21600000000000L,"causal monotonic clock range");
        return Math.toIntExact(Math.max(0,(elapsed+(upper?999999:0))/1000000+(upper?1:-1)));
    }
    void received(int connection,int ordinal,int lower,int upper){
        Phase18Plan.require(lower>=0&&upper>=lower&&upper<21600000,"causal callback bracket");
        int offset=Math.multiplyExact(ordinal,7);byte[] row=bounds[connection];long previous=0;
        for(int i=0;i<7;i++)previous=(previous<<8)|(row[offset+i]&255L);
        Phase18Plan.require(previous==0,"causal duplicate receipt");
        // 두 25-bit 값(각 6시간 미만)을 7 bytes로 손실 없이 보유. 파일 계약은 두 int32 그대로.
        long packed=((long)lower<<25)|upper;
        for(int i=6;i>=0;i--){row[offset+i]=(byte)packed;packed>>>=8;}
    }
    void close()throws IOException{
        try(var stream=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output,StandardOpenOption.CREATE_NEW),65536))){
            for(var connection:bounds)for(int offset=0;offset<connection.length;offset+=7){
                long value=0;for(int i=0;i<7;i++)value=(value<<8)|(connection[offset+i]&255L);
                stream.writeLong(((value>>>25)<<32)|(value&0x1ffffffL));
            }
        }
        bounds=null; // callback 소진 후 성공한 마감에서만 heap 보유 해제
    }
}
