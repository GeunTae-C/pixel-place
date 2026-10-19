package dev.cgt.benchmark;

import tools.jackson.databind.JsonNode;
import java.io.DataOutputStream;
import java.nio.file.*;
import java.util.*;

/** 고유 seq의 primitive canonical 저장소와 연결별 수신 bitset. 도착 순서와 seq gap에 무관한 원자적 ordinal */
final class Phase18Events {
    record Event(long eventSeq, long tileVersion, int x, int y, int color) {
        Event {
            Phase18Plan.require(eventSeq > 0 && tileVersion > 0 && x >= 0 && x < 8192 && y >= 0 && y < 8192
                    && color >= 0 && color <= 255, "event range");
        }
    }
    private final long[] keys, seq, version;
    private final int[] ordinals, x, y, color;
    private final long[][] received;
    private final long[] accepted, deliveries, duplicates;
    private int size;
    private java.io.RandomAccessFile timing;
    private long[][] bufferedTimes;
    private long timingArrayBytes;
    private long[] lastSeq, reversed;
    Phase18Events(int maximum,int connections,Path timingFile)throws Exception {
        this(maximum,connections,timingFile,false);
    }
    Phase18Events(int maximum,int connections,Path timingFile,boolean buffered)throws Exception {
        this(maximum,connections,timingFile,buffered,false);
    }
    Phase18Events(int maximum,int connections,Path timingFile,boolean buffered,boolean cap1)throws Exception {
        this(maximum,connections,timingFile!=null,cap1);
        if(timingFile!=null){
            long bytes=Math.multiplyExact(Math.multiplyExact((long)maximum,connections),8);
            if(buffered){
                Phase18Plan.require(bytes<=(cap1?Phase18Cap1.TIMING_MEMORY:128L*1024*1024),"bounded k6 timing arrays");
                bufferedTimes=new long[connections][maximum];timingArrayBytes=bytes+connections*24L;
            }
            Files.createFile(timingFile);timing=new java.io.RandomAccessFile(timingFile.toFile(),"rw");
            timing.setLength(bytes);
        }
    }
    Phase18Events(int maximum, int connections) {this(maximum,connections,false);}
    private Phase18Events(int maximum,int connections,boolean cap) {this(maximum,connections,cap,false);}
    private Phase18Events(int maximum,int connections,boolean cap,boolean cap1) {
        Phase18Plan.require(maximum >= 0 && maximum <= (cap1?Phase18Cap1.MAX_EVENTS:200_000) && connections >= 0 && connections <= (cap?3200:100), "WS bounds");
        int slots = 1; while (slots < Math.max(1, maximum * 2)) slots <<= 1;
        keys = new long[slots]; ordinals = new int[slots]; seq = new long[maximum]; version = new long[maximum];
        x = new int[maximum]; y = new int[maximum]; color = new int[maximum];
        received = new long[connections][(maximum + 63) / 64]; accepted = new long[(maximum + 63) / 64];
        deliveries = new long[connections]; duplicates = new long[connections];lastSeq=new long[connections];reversed=new long[connections];
    }
    private int intern(Event e) {
        int slot = (int) ((e.eventSeq ^ (e.eventSeq >>> 32)) * 0x9e3779b9L) & (keys.length - 1);
        while (keys[slot] != 0 && keys[slot] != e.eventSeq) slot = (slot + 1) & (keys.length - 1);
        if (keys[slot] != 0) {
            int n = ordinals[slot];
            if (!event(n).equals(e)) throw new IllegalStateException("WS/HTTP canonical payload mismatch");
            return n;
        }
        if (size == seq.length) throw new IllegalStateException("WS unique event ceiling");
        int n = size++; keys[slot] = seq[n] = e.eventSeq; ordinals[slot] = n;
        version[n] = e.tileVersion; x[n] = e.x; y[n] = e.y; color[n] = e.color; return n;
    }
    synchronized void accepted(Event event) { int n = intern(event); accepted[n >>> 6] |= 1L << n; }
    synchronized void received(int connection,Event event){
        try{received(connection,event,System.nanoTime());}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    synchronized int received(int connection, Event event,long receivedNanos)throws java.io.IOException {
        Objects.checkIndex(connection, received.length); int n = intern(event); long bit = 1L << n;
        deliveries[connection]++;
        if ((received[connection][n >>> 6] & bit) != 0) duplicates[connection]++;
        else if(bufferedTimes!=null)bufferedTimes[connection][n]=receivedNanos;
        else if(timing!=null){timing.seek(((long)connection*seq.length+n)*8);timing.writeLong(receivedNanos);}
        if(lastSeq[connection]>event.eventSeq())reversed[connection]++;lastSeq[connection]=event.eventSeq();
        received[connection][n >>> 6] |= bit;return n;
    }
    synchronized void closeTiming()throws java.io.IOException {
        if(timing!=null){
            if(bufferedTimes!=null){
                // 동시 WS 수신에서 delivery마다 여러 native write를 하지 않도록 동결 timing 한도만큼만 보유, 64KiB씩 파일화
                timing.seek(0);var block=java.nio.ByteBuffer.allocate(65536);
                for(var connection:bufferedTimes)for(long value:connection){
                    block.putLong(value);if(!block.hasRemaining()){timing.write(block.array());block.clear();}
                }
                if(block.position()>0)timing.write(block.array(),0,block.position());
                bufferedTimes=null;
            }
            timing.getFD().sync();timing.close();timing=null;
        }
    }
    synchronized Event event(int n) { Objects.checkIndex(n, size); return new Event(seq[n], version[n], x[n], y[n], color[n]); }
    synchronized int size() { return size; }
    /** canonical JSONL의 0-based 행 ordinal과 같은 bit 위치. 연결 순서/word 순서의 big-endian long 저장 */
    synchronized void saveReceipts(Path path) throws Exception {
        try(var out=new DataOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW))) {
            for(long[] connection:received)for(long word:connection)out.writeLong(word);
        }
    }
    /** callback 소진 뒤 exact 수신 집합과 중복을 별개로 반환. WS 선행 이벤트를 HTTP 성공으로 승격하지 않음 */
    synchronized Map<String, Object> report() {
        var missing = new ArrayList<Long>(); var extra = new ArrayList<Long>();
        for (long[] set : received) {
            long m = 0, e = 0;
            for (int w = 0; w < set.length; w++) { m += Long.bitCount(accepted[w] & ~set[w]); e += Long.bitCount(set[w] & ~accepted[w]); }
            missing.add(m); extra.add(e);
        }
        return Map.of("uniqueEvents", size, "deliveries", deliveries.clone(), "duplicates", duplicates.clone(),
                "missing", missing, "extra", extra, "bitsetBytes", (long) received.length * accepted.length * 8,
                "retainedArrayBytes", retainedArrayBytes(),"arrivalReversals",reversed.clone());
    }
    /** 실제 수신 bit와 accepted bit의 일치. 중복/extra도 완료로 숨기지 않음 */
    synchronized boolean complete() {
        for(int connection=0;connection<received.length;connection++){
            if(duplicates[connection]!=0)return false;
            for(int word=0;word<accepted.length;word++)if(received[connection][word]!=accepted[word])return false;
        }
        return true;
    }
    long retainedArrayBytes() {
        // array header/정렬·연결 참조를 포함한 보수적 상한. 객체 payload/boxed map을 고유 건수마다 만들지 않음
        return keys.length * 12L + seq.length * 28L + (received.length + 1L) * accepted.length * 8
                + received.length * 64L + 544 + timingArrayBytes;
    }
    static Event parse(JsonNode value) {
        Phase18Plan.require(value.isObject() && value.size() == 6 && value.path("type").asText().equals("pixel"), "WS shape");
        for (String field : List.of("eventSeq", "tileVersion", "x", "y", "color"))
            Phase18Plan.require(value.path(field).isIntegralNumber() && value.path(field).canConvertToLong(), "WS integer field");
        for (String field : List.of("x", "y", "color")) Phase18Plan.require(value.path(field).canConvertToInt(), "WS coordinate integer");
        return new Event(value.path("eventSeq").longValue(), value.path("tileVersion").longValue(), value.path("x").intValue(), value.path("y").intValue(), value.path("color").intValue());
    }
}
