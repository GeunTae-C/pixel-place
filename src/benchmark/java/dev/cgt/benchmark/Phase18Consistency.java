package dev.cgt.benchmark;

import dev.cgt.pixelplace.tile.domain.*;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 작은 기능 fixture의 알려진 입력/원시 결과와 DB·잔존 WAL·memory 직접 대조. 분포/SLO 분석기 책임 없음 */
final class Phase18Consistency {
    private Phase18Consistency() { }
    static List<Phase18Raw> read(Path file,Phase18Plan.Loaded loaded) throws Exception {
        return read(file,loaded,loaded.plan().cases().getFirst().caseId().startsWith("stop"));
    }
    /** 상위 중단 정리의 실제 미송신만 허용. UNKNOWN/timeout을 정상 결과로 복원하면 안 됨 */
    static List<Phase18Raw> readStopped(Path file,Phase18Plan.Loaded loaded) throws Exception {
        return read(file,loaded,true);
    }
    private static List<Phase18Raw> read(Path file,Phase18Plan.Loaded loaded,boolean allowStopped) throws Exception {
        var c=loaded.plan().cases().getFirst();long planned=loaded.plan().counts(c).writes()+loaded.plan().counts(c).reads();
        Phase18Plan.require(planned<=128&&Files.size(file)<=planned*2048,"small raw fixture bound");
        var rows=new ArrayList<Phase18Raw>();var seen=new HashSet<String>();
        try(var input=Files.newBufferedReader(file)) {
            String line;while((line=input.readLine())!=null){
                Phase18Plan.require(rows.size()<planned&&line.length()<=2048,"raw row bound");
                var r=Phase18Plan.JSON.readValue(line,Phase18Raw.class);
                Phase18Plan.require(r.schemaVersion()==1&&r.planHash().equals(loaded.planHash())&&r.runId().equals(c.runId())&&r.caseId().equals(c.caseId())
                        &&r.requestId().equals(Phase18Plan.requestId(c,r.kind(),r.scheduledPhase(),r.ordinal()))&&seen.add(r.requestId()),"raw identity");
                boolean stopped=allowStopped&&r.status().equals("client_not_sent")&&r.sendState().equals("not_sent")&&r.outcome().equals("NOT_SENT")
                        &&r.reason().equals("stopped")&&r.sentNanos()==null&&r.completedNanos()==null;
                Phase18Plan.require(stopped||r.status().equals("accepted")&&r.sendState().equals("sent")&&r.outcome().equals("ACCEPTED")&&r.httpStatus()==200
                        &&r.completedNanos()>=r.sentNanos()&&r.decidedNanos()<=r.completedNanos(),"normal fixture HTTP result");
                rows.add(r);
            }
        }
        Phase18Plan.require(rows.size()==planned,"frozen planned terminal completeness");return List.copyOf(rows);
    }
    static Map<String,Object> verify(BenchmarkFixtures fixture,ConfigurableApplicationContext context,List<Long> users,List<Phase18Raw> rows) throws Exception {
        var accepted=new TreeMap<Long,Phase18Raw>();var events=new ArrayList<Phase18Events.Event>();
        var samples=new ArrayList<Phase18ReadReplay.Sample>();
        for(var row:rows)if(row.status().equals("accepted")&&row.kind().equals("write")){
            Phase18Plan.require(row.eventSeq()!=null&&accepted.put(row.eventSeq(),row)==null,"unique accepted seq");
            events.add(new Phase18Events.Event(row.eventSeq(),row.tileVersion(),row.x(),row.y(),row.color()));
        }else if(row.rawSha256()!=null)samples.add(new Phase18ReadReplay.Sample(row.x()/256,row.y()/256,row.tileVersion(),row.rawSha256()));
        var pixels=new HashMap<TileKey,byte[]>();var versions=new HashMap<TileKey,Long>();var timestamps=new HashMap<Long,LocalDateTime>();
        for(var key:new CanonicalZ0TileKeys().orderedKeys()){pixels.put(key,TileState.allWhite().pixels());versions.put(key,0L);}
        int count=0;long last=0;
        try(var c=fixture.connect(true);var s=c.createStatement()) {
            try(var db=s.executeQuery("SELECT event_seq,user_id,z,tx,ty,x,y,color,created_at FROM pixel_events ORDER BY event_seq")){
                while(db.next()){
                    long seq=db.getLong(1);var row=accepted.get(seq);Phase18Plan.require(row!=null&&seq>last,"DB event correspondence");last=seq;count++;
                    Phase18Plan.require(db.getLong(2)==users.get(row.userOrdinal())&&db.getInt(3)==0&&db.getInt(4)==row.x()/256&&db.getInt(5)==row.y()/256
                            &&db.getInt(6)==row.x()&&db.getInt(7)==row.y()&&db.getInt(8)==row.color(),"DB accepted payload");
                    var key=new TileKey(0,row.x()/256,row.y()/256);long version=versions.merge(key,1L,Long::sum);
                    Phase18Plan.require(row.tileVersion()==version,"accepted per-tile version");pixels.get(key)[(row.y()%256)*256+row.x()%256]=(byte)row.color();
                    timestamps.put(seq,db.getObject(9,LocalDateTime.class));
                }
            }
            Phase18Plan.require(count==accepted.size(),"all accepted stored");var seen=new HashSet<TileKey>();
            try(var db=s.executeQuery("SELECT z,tx,ty,data,tile_version FROM tiles")){
                while(db.next()){
                    var key=new TileKey(db.getInt(1),db.getInt(2),db.getInt(3));Phase18Plan.require(pixels.containsKey(key)&&seen.add(key),"canonical DB key");
                    Phase18Plan.require(Arrays.equals(db.getBytes(4),pixels.get(key))&&db.getLong(5)==versions.get(key),"DB snapshot bytes/version");
                }
            }
            Phase18Plan.require(seen.size()==(accepted.isEmpty()?0:1024),"canonical DB count");
        }
        var board=context.getBean(InMemoryTileBoard.class);Phase18Plan.require(board.size()==1024,"memory canonical count");
        for(var key:pixels.keySet()){var memory=board.getRequired(key);Phase18Plan.require(memory.tileVersion()==versions.get(key)&&Arrays.equals(memory.pixels(),pixels.get(key)),"memory final snapshot");}
        var batch=context.getBean(WalReplaySource.class).readAfter(0);var retained=new HashSet<Long>();
        for(var record:batch.records()){
            var row=accepted.get(record.eventSeq());Phase18Plan.require(row!=null&&retained.add(record.eventSeq())&&record.userId()==users.get(row.userOrdinal())
                    &&record.z()==0&&record.tx()==row.x()/256&&record.ty()==row.y()/256&&record.x()==row.x()&&record.y()==row.y()&&record.color()==row.color(),"retained WAL payload");
            Phase18Plan.require(record.createdAt().truncatedTo(ChronoUnit.MILLIS).equals(timestamps.get(record.eventSeq())),"WAL original/DB millisecond createdAt");
        }
        // 제한 fixture는 기본 segment 안에 전량 잔존. 삭제된 이력을 복원했다고 주장하지 않음
        Phase18Plan.require(retained.equals(accepted.keySet())&&fixture.checkpoint()==last&&batch.walLastEventSeq()==last,"DB/WAL exact interval/checkpoint");
        Phase18ReadReplay.verify(samples,events);
        return Map.of("complete",true,"accepted",accepted.size(),"reads",rows.stream().filter(r->r.kind().equals("read")&&r.status().equals("accepted")).count(),"readSamples",samples.size(),"checkpoint",last,
                "retainedWalRecords",retained.size(),"canonicalMemory",1024,"databaseTiles",accepted.isEmpty()?0:1024,"createdAt","WAL original -> DB milliseconds");
    }
}
