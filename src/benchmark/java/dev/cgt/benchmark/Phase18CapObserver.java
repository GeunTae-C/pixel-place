package dev.cgt.benchmark;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 발생기 시계의 HTTP 검증 완료와 소유 checkpoint 읽기 전용 관측. SQL 연결/파일의 명시 소유 */
final class Phase18CapObserver implements AutoCloseable {
    private final Phase18Plan.Loaded loaded;
    private final Path output;
    private final String clock;
    private final boolean epoch;
    private final BufferedWriter http,db;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"cap-checkpoint-observer"));
    private final Connection connection;
    private final Phase18Deadline deadline;
    private final Consumer<Throwable> failure;
    private final long connectionId;
    private long queries,queryNanos,httpRows;
    private volatile boolean stopped;
    private boolean closed;
    Phase18CapObserver(Phase18Plan.Loaded loaded,Path output,String clock,Phase18Deadline deadline,Consumer<Throwable> failure)throws Exception {
        this.loaded=loaded;this.output=output;this.clock=clock;this.epoch=clock.equals("phase18-k6-epoch-millis");this.deadline=deadline;this.failure=failure;
        var p=loaded.plan();var credentials=BenchmarkEnvironment.credentials();Phase18Fixtures.credentials(credentials,p);
        var env=Phase18Environment.read(Path.of(System.getProperty("phase18.environment")),p);
        var props=new Properties();props.setProperty("user",credentials.dbUsername());props.setProperty("password",credentials.dbPassword());
        props.setProperty("connectTimeout","2000");props.setProperty("socketTimeout","2000");props.setProperty("allowMultiQueries","false");
        Connection owned=null;BufferedWriter h=null,d=null;
        try {
            owned=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3307/"+p.ownership().catalog(),props);
            owned.setReadOnly(true);BenchmarkGuards.requireCatalog(owned,p.ownership().catalog());
            try(var statement=owned.createStatement();var row=statement.executeQuery("SELECT @@server_uuid,@@port,DATABASE(),CONNECTION_ID()")){
                Phase18Plan.require(row.next()&&row.getString(1).equals(env.mysqlUuid())&&row.getInt(2)==3307&&row.getString(3).equals(p.ownership().catalog()),"CAP observer owned catalog/identity");
                connectionId=row.getLong(4);
            }
            h=Files.newBufferedWriter(output.resolve("cap-http.jsonl"),StandardOpenOption.CREATE_NEW);
            d=Files.newBufferedWriter(output.resolve("cap-db.jsonl"),StandardOpenOption.CREATE_NEW);
        }catch(Exception|Error error){if(h!=null)h.close();if(d!=null)d.close();if(owned!=null)owned.close();worker.shutdown();throw error;}
        connection=owned;http=h;db=d;
        worker.scheduleAtFixedRate(this::sample,0,1,TimeUnit.SECONDS);
    }
    private void sample(){
        if(stopped)return;
        try {
            deadline.check();long before=System.nanoTime(),begin=epoch?System.currentTimeMillis():before,checkpoint;
            try(var statement=connection.createStatement()){
                statement.setQueryTimeout(2);
                try(var rows=statement.executeQuery("SELECT last_flushed_event_seq FROM wal_checkpoint WHERE checkpoint_name='main'")){
                    Phase18Plan.require(rows.next(),"CAP checkpoint missing");checkpoint=rows.getLong(1);Phase18Plan.require(!rows.next()&&checkpoint>=0,"CAP checkpoint shape");
                }
            }
            long end=epoch?System.currentTimeMillis():System.nanoTime();
            if(++queries>(Phase18Cap1.enabled(loaded.plan())?loaded.plan().bounds().totalSeconds()+2:3602))throw new IOException("CAP checkpoint sample bound");queryNanos+=System.nanoTime()-before;
            db.write(Phase18Plan.JSON.writeValueAsString(Map.of("planHash",loaded.planHash(),"clockId",clock,epoch?"queryStartEpochMillis":"queryStartNanos",begin,epoch?"queryEndEpochMillis":"queryEndNanos",end,"checkpoint",checkpoint)));
            db.newLine();db.flush();
        }catch(Exception|Error problem){stopped=true;failure.accept(problem);}
    }
    /** body수신과 요청 검증 완료의 max 이후181초를 pool에 전달하기 위한 고정 경계 */
    synchronized long http(String id,String phase,long sent,long body,Long seq,Integer user,Integer attempt,String outcome)throws Exception {
        long confirmed=Math.max(body,System.nanoTime());
        if(++httpRows>Phase18Cap.MAX_TERMINALS)throw new IOException("CAP HTTP observer bound");
        var row=new LinkedHashMap<String,Object>();row.put("planHash",loaded.planHash());row.put("clockId",clock);row.put("requestId",id);
        row.put("phase",phase);row.put("sentNanos",sent);row.put("bodyNanos",body);row.put("validatedNanos",confirmed);row.put("eventSeq",seq);
        row.put("userOrdinal",user);row.put("userAttempt",attempt);row.put("outcome",outcome);
        http.write(Phase18Plan.JSON.writeValueAsString(row));http.newLine();http.flush();return confirmed;
    }
    public synchronized void close()throws Exception {
        if(closed)return;stopped=true;worker.shutdown();
        if(!worker.awaitTermination(10000,TimeUnit.MILLISECONDS))throw new TimeoutException("CAP observer still owned");
        Throwable problem=null;
        try{connection.close();}catch(Exception e){problem=e;}
        try{http.close();}catch(Exception e){problem=Phase18Process.preserve(problem,e);}
        try{db.close();}catch(Exception e){problem=Phase18Process.preserve(problem,e);}
        Phase18Process.rethrow(problem);closed=true;
        BenchmarkJson.write(output.resolve("cap-observer.json"),Map.of("schemaVersion",epoch?3:2,"planHash",loaded.planHash(),"clockId",clock,
                "connectionId",connectionId,"connectionClosed",connection.isClosed(),"queries",queries,"queryNanos",queryNanos,
                "httpRows",httpRows,"hashes",Map.of("cap-http.jsonl",BenchmarkJson.hash(output.resolve("cap-http.jsonl")),"cap-db.jsonl",BenchmarkJson.hash(output.resolve("cap-db.jsonl")))));
    }
}
