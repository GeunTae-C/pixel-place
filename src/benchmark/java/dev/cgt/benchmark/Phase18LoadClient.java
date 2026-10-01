package dev.cgt.benchmark;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 별도 JVM의 독립 write/read open-loop 생산자. 처리 슬롯은 수신·검증·raw 저장까지 소유하며 STOP과 실제 dispatch 직렬화 */
public final class Phase18LoadClient {
    private final Phase18Plan.Loaded loaded;
    private final Phase18Plan.Case c;
    private final Path output;
    private final String endpoint, clock = Phase18Raw.clock();
    private final List<Long> users;
    private final Phase18Control control = new Phase18Control();
    private final BlockingQueue<String> messages = new ArrayBlockingQueue<>(16);
    private final ExecutorService callbacks = Executors.newFixedThreadPool(8);
    private final HttpClient http;
    private final BenchmarkUsers pool;
    private final Phase18Tokens tokens;
    private final Phase18Events events;
    private final BufferedWriter raw;
    private final List<WebSocket> sockets = new ArrayList<>();
    private final List<CompletableFuture<Void>> socketClosed = new ArrayList<>();
    private final long[] wsOpened, wsClosedAt;
    private final Map<String,BitSet> recorded = new HashMap<>();
    private boolean socketsFinished, rawFailed;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicLong rows = new AtomicLong(), sent = new AtomicLong();
    private final ConcurrentMap<String, Map<String,Object>> activeRequests = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Map<String,Object>> unknownRequests = new ConcurrentHashMap<>();
    private final Map<String, Object> anchors = new LinkedHashMap<>();
    private volatile boolean closing, stopped;
    private final Phase18Deadline execution;
    private final InputStream controlInput;
    private final java.util.function.Consumer<String> protocolOutput;
    private Phase18JvmSampler sampler;

    private Phase18LoadClient(Phase18Plan.Loaded loaded, Path input) throws Exception {
        this(loaded,input,System.in,new BenchmarkEnvironment.Keys(System.getenv("PIXEL_PLACE_BENCH_JWT_KEY"),System.getenv("PIXEL_PLACE_BENCH_COOKIE_KEY")),value->{System.out.println(value);System.out.flush();});
    }
    /** JVM 표준 입출력과 시험 파이프가 동일 수신·dispatch·소진 경로를 사용하도록 경계 주입 */
    Phase18LoadClient(Phase18Plan.Loaded loaded,Path input,InputStream controlInput,BenchmarkEnvironment.Keys keys,
            java.util.function.Consumer<String> protocolOutput) throws Exception {
        this.controlInput=controlInput;this.protocolOutput=protocolOutput;
        this.loaded = loaded; c = loaded.plan().cases().getFirst(); var w = c.workload();
        execution = Phase18Deadline.inherit(loaded.plan());
        var start = BenchmarkJson.read(input); output = Phase18Paths.checked(start.path("output").asText());
        Phase18Paths.child(output, Path.of(loaded.plan().ownership().evidenceRoot()));
        endpoint = start.path("endpoint").asText(); Phase18Plan.require(endpoint.matches("http://127\\.0\\.0\\.1:[0-9]{1,5}"), "loopback endpoint");
        var ids = new ArrayList<Long>(); for (var id : start.path("users")) ids.add(id.longValue()); users = List.copyOf(ids);
        Phase18Plan.require(users.size() == loaded.plan().counts(c).users(), "fixture users");
        pool = new BenchmarkUsers(users.size(), w.userMode().equals("once"), w.reuseMarginMillis(), w.bootstrap());
        var auth = new AuthProperties(keys.jwt(), keys.cookie(), Duration.ofMinutes(15));
        tokens = new Phase18Tokens(new ServiceJwtTokens(auth, Clock.systemUTC()), Clock.systemUTC(), Math.max(1, (int) w.writeInFlight()));
        events = new Phase18Events((int) loaded.plan().counts(c).wsEvents(), (int) w.wsConnections());
        wsOpened=new long[(int)w.wsConnections()];wsClosedAt=new long[wsOpened.length];
        for(String phase:List.of("bootstrap","warmup","measurement"))for(String kind:List.of("write","read"))recorded.put(kind+"/"+phase,new BitSet());
        http = HttpClient.newBuilder().executor(callbacks).connectTimeout(Duration.ofMillis(w.requestMillis()))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
        raw = Files.newBufferedWriter(output.resolve("raw.jsonl"), StandardOpenOption.CREATE_NEW);
        Thread.ofPlatform().daemon(true).name("phase18-load-control").start(this::readControl);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("plan and start input required");
        var loaded = Phase18Plan.read(Phase18Paths.checked(args[0])); Phase18Runtime.verify(loaded.plan());
        var client = new Phase18LoadClient(loaded, Phase18Paths.checked(args[1])); Throwable failure = null;
        try { client.run(); } catch (Exception | Error problem) { failure = problem; client.fail(problem); }
        finally { try { client.close(); } catch (Exception | Error problem) { failure = Phase18Process.preserve(failure, problem); } }
        Phase18Process.rethrow(failure);
    }
    private void readControl() {
        try {
            var in = new BufferedReader(new InputStreamReader(controlInput, StandardCharsets.UTF_8)); String message;
            while ((message = boundedControl(in)) != null) {
                if (message.equals("STOP") || message.equals("CLOSE")) {
                    long received = System.nanoTime(); long applied = control.stop();
                    say("APPLIED " + control.snapshot().dispatched() + " " + received + " " + applied);
                    stopped = true;
                    messages.offer("STOP");
                } else if (!messages.offer(message)) throw new IOException("Control queue limit");
            }
            if (!closing) fail(new EOFException("Parent control EOF"));
        } catch (Exception | Error problem) { if (!closing) fail(problem); }
    }
    private static String boundedControl(BufferedReader in) throws IOException {
        var line = new StringBuilder(); int b;
        while ((b = in.read()) != -1 && b != '\n') { if (b != '\r') line.append((char)b); if (line.length() > 128) throw new IOException("Control size"); }
        return b == -1 && line.isEmpty() ? null : line.toString();
    }
    private void await(String expected) throws Exception {
        try {
        check();
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(execution.capMillis(Phase18Execution.responseWaitMillis(expected,c.workload())));
        while (!stopped && System.nanoTime() < until) {
            check(); String value = messages.poll(10, TimeUnit.MILLISECONDS);
            check();
            if(System.nanoTime()>=until)throw new TimeoutException("Control deadline: "+expected);
            if (value == null) continue;
            if (value.equals("STOP")) return;
            if (!value.equals(expected)) throw new IOException("Unexpected control transition");
            return;
        }
        if (!stopped) throw new TimeoutException("Control deadline: "+expected);
        } catch(Exception|Error problem) {
            // poll/기한 검사와 경합한 callback 실패도 최초 원인 identity로 재전파
            fail(problem);Phase18Process.rethrow(Phase18Process.preserve(failure.get(),problem));
        }
    }
    private void run() throws Exception {
        if(Phase18Execution.observed(loaded.plan()))sampler=new Phase18JvmSampler(loaded,output,this::fail);
        // 앱의 시작 인계가 확정되기 전 bootstrap을 포함한 신규 HTTP 차단
        await("START");
        if (c.workload().bootstrap()) {
            phase("bootstrap", 0, true); drain(); say("BOOTSTRAP " + sent.get()); await("CONNECT");
        }
        if (!stopped) connectWs(); say("READY"); await("WARMUP");
        phase("warmup", c.workload().warmupSeconds(), false); drain(); say("WARMUP_DONE " + sent.get());
        await("MEASURE"); phase("measurement", c.workload().measurementSeconds(), false);
        say("SEND_DONE " + sent.get()); drain();
        if (!stopped) awaitWs();
        finishSockets();
        if (!stopped) awaitWs();
        say("DRAINED " + control.snapshot().completed());
        raw.flush();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("schemaVersion", 1); summary.put("planHash", loaded.planHash()); summary.put("clockId", clock);
        summary.put("anchors", anchors); summary.put("planned", loaded.plan().counts(c)); summary.put("terminalRows", rows.get());
        summary.put("sent", sent.get()); summary.put("control", control.snapshot()); summary.put("stopped", stopped);
        summary.put("tokens", tokens.report()); summary.put("ws", events.report()); summary.put("runtime", runtime());
        summary.put("wsOpenedNanos",wsOpened);summary.put("wsClosedNanos",wsClosedAt);summary.put("wsCallbacksDrained",socketsFinished);
        summary.put("wsReceiptEncoding",Map.of("schemaVersion",1,"canonicalOrdinal","JSONL zero-based row","wordsPerConnection",(loaded.plan().counts(c).wsEvents()+63)/64,"byteOrder","BIG_ENDIAN","file","ws-received.bin"));
        BenchmarkJson.write(output.resolve("producer.json"), summary);
        saveWs();
        check();say("DONE " + rows.get());
    }
    private void phase(String phase, long seconds, boolean bootstrap) throws Exception {
        long anchor = System.nanoTime(); anchors.put(phase, Map.of("nanos", anchor, "endNanos",anchor+TimeUnit.SECONDS.toNanos(seconds),"utc", Instant.now().toString()));
        if (bootstrap) { send("write", phase, 0, 0, anchor, new Semaphore(1), true); return; }
        // pacing thread 2개만 사용. 처리 callback은 각 kind의 semaphore 반환 전까지 소유
        try (var pacing = Executors.newFixedThreadPool(2)) {
            var write = pacing.submit(() -> { window("write", phase, anchor, seconds); return null; });
            var read = pacing.submit(() -> { window("read", phase, anchor, seconds); return null; });
            for (var future : List.of(write, read)) try { future.get(); }
            catch (ExecutionException e) { Phase18Process.rethrow(e.getCause()); }
        }
    }
    private void window(String kind, String phase, long anchor, long seconds) throws Exception {
        var w = c.workload(); long rate = kind.equals("write") ? w.writeRate() : w.readRate();
        var slots = new Semaphore((int) (kind.equals("write") ? w.writeInFlight() : w.readInFlight()));
        long planned = Math.multiplyExact(rate, seconds), end = anchor + TimeUnit.SECONDS.toNanos(seconds);
        for (long i = 0; i < planned; i++) {
            long offset = Phase18Plan.scheduledOffsetNanos(i, rate), due = anchor + offset;
            while (!stopped && System.nanoTime() < due) { check(); TimeUnit.NANOSECONDS.sleep(Math.max(0, Math.min(1_000_000, due - System.nanoTime()))); }
            long now = System.nanoTime();
            if (stopped) { notSent(kind, phase, i, offset, "stopped", null); continue; }
            check();
            if (now >= end || now - due >= 1_000_000_000L/rate) { notSent(kind, phase, i, offset, "arrival_late", null); continue; }
            send(kind, phase, i, offset, due, slots, false);
        }
        while(!stopped&&System.nanoTime()<end){check();TimeUnit.NANOSECONDS.sleep(Math.max(0,Math.min(1_000_000,end-System.nanoTime())));}
    }
    private void send(String kind, String phase, long ordinal, long offset, long due, Semaphore slots, boolean bootstrap) throws Exception {
        if (!slots.tryAcquire()) { notSent(kind, phase, ordinal, offset, "processing_capacity", null); return; }
        BenchmarkUsers.Assignment user = null;
        try {
            int[] pixel = pixel(c, phase, ordinal); String token = null;
            if (kind.equals("read")) {
                long n = ordinal + (phase.equals("measurement") ? c.workload().readRate()*c.workload().warmupSeconds() : 0);
                pixel = c.workload().readPattern().equals("hot") ? new int[]{0,0,0} : new int[]{(int)(n%32)*256,(int)((n/32)%32)*256,0};
            }
            if (kind.equals("write")) {
                user = bootstrap ? new BenchmarkUsers.Assignment(0, 1) : pool.acquire(System.nanoTime());
                if (user == null) { notSent(kind, phase, ordinal, offset, "user_pool", null); slots.release(); return; }
                token = tokens.get(users.get(user.ordinal()));
            }
            String id = Phase18Plan.requestId(c, kind, phase, ordinal);
            var builder = HttpRequest.newBuilder().timeout(Duration.ofMillis(c.workload().requestMillis())).header("X-Phase18-Request", id);
            if (kind.equals("write")) builder.uri(URI.create(endpoint + "/api/pixels")).header("Authorization", "Bearer " + token)
                    .header("Origin", "http://localhost:3000").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"x\":"+pixel[0]+",\"y\":"+pixel[1]+",\"color\":"+pixel[2]+"}"));
            else builder.uri(URI.create(endpoint + "/api/tiles/0/" + (pixel[0]/256) + "/" + (pixel[1]/256))).GET();
            var request = builder.build(); var assignment = user; int[] actualPixel = pixel;
            boolean dispatched = control.dispatch(() -> {
                long sentAt = System.nanoTime(); sent.incrementAndGet();
                activeRequests.put(id,Map.of("requestId",id,"sendState","unknown","reason","dispatch_in_progress","lastObservedNanos",sentAt));
                try {
                    var receivedAt=new AtomicLong();
                    var responseFuture=http.sendAsync(request, info -> new Phase18TileBody.Subscriber(kind.equals("read") ? 131072 : 8192,receivedAt::set));
                    activeRequests.put(id,Map.of("requestId",id,"sendState","sent","reason","response_pending","lastObservedNanos",sentAt));
                    responseFuture.whenComplete((response, problem) -> complete(kind, phase, ordinal, offset, sentAt, receivedAt.get(), assignment, actualPixel, response, problem, slots, bootstrap, "sent"));
                } catch (RuntimeException problem) {
                    complete(kind, phase, ordinal, offset, sentAt, 0, assignment, actualPixel, null, problem, slots, bootstrap, "unknown");
                }
                return null;
            });
            if (!dispatched) {
                if (assignment != null && !bootstrap) pool.complete(assignment, System.nanoTime(), false);
                notSent(kind, phase, ordinal, offset, "stopped", assignment); slots.release();
            }
        } catch (Exception | Error problem) {
            fail(problem); throw problem;
        }
    }
    private void complete(String kind, String phase, long ordinal, long offset, long sentAt, long receivedAt, BenchmarkUsers.Assignment user,
            int[] pixel, HttpResponse<byte[]> response, Throwable problem, Semaphore slots, boolean bootstrap, String sendState) {
        // body 완료 시각은 callback 대기·압축 해제·증거 저장보다 앞에서 확정. 처리 슬롯은 계속 소유
        long completed = response!=null&&receivedAt!=0?receivedAt:System.nanoTime(); String status, reason = "", outcome = "REJECTED";
        Integer code = response == null ? null : response.statusCode(); Long seq = null, version = null, validation = null;
        String hash = null; Integer wire = null;
        boolean saved=false;
        try {
            if (problem != null) {
                boolean timeout = false; for (Throwable t = problem; t != null; t = t.getCause()) if (t instanceof HttpTimeoutException) timeout = true;
                status = timeout ? "timeout" : "network_error"; outcome = "UNKNOWN"; reason = status;
            } else if (code == 200) {
                try {
                    if (kind.equals("write")) {
                        var body = Phase18Plan.JSON.readTree(response.body());
                        Phase18Plan.require(body.isObject() && body.size() == 6 && body.path("accepted").isBoolean() && body.path("accepted").booleanValue(), "accepted shape");
                        for (String f : List.of("eventSeq", "tileVersion", "x", "y", "color")) Phase18Plan.require(body.path(f).isIntegralNumber() && body.path(f).canConvertToLong(), "accepted integer");
                        Phase18Plan.require(body.path("x").longValue() == pixel[0] && body.path("y").longValue() == pixel[1] && body.path("color").longValue() == pixel[2], "accepted payload");
                        seq = body.path("eventSeq").longValue(); version = body.path("tileVersion").longValue();
                        var event = new Phase18Events.Event(seq, version, pixel[0], pixel[1], pixel[2]);
                        if (!bootstrap && c.workload().wsConnections() > 0) events.accepted(event);
                    } else {
                        var result = Phase18TileBody.verify(response.headers(), response.body()); version = result.tileVersion(); wire = result.wireBytes(); validation = result.validationNanos();
                        long global = ordinal + (phase.equals("measurement") ? c.workload().readRate()*c.workload().warmupSeconds() : 0);
                        if (Phase18ReadReplay.sampled(global, loaded.plan().counts(c).reads())) hash = result.sha256();
                    }
                    status = "accepted"; outcome = "ACCEPTED";
                } catch (Exception malformed) { status = "malformed_200"; outcome = "UNKNOWN"; reason = "payload_validation"; }
            } else if (code == 429) status = "rejected_429";
            else if (code >= 400 && code < 500) status = "rejected_4xx";
            else if (code >= 500 && code < 600) {
                status = "server_5xx"; outcome = "UNKNOWN";
                if (code == 503) try { String message = Phase18Plan.JSON.readTree(response.body()).path("message").asText();
                    if (message.equals("Pixel write is busy. Please retry later.")) { reason = "write_busy"; outcome = "REJECTED"; }
                    else if (message.equals("Pixel write outcome is unknown.")) reason = "write_unknown";
                } catch (RuntimeException ignored) { /* 비민감 label만 기록 */ }
            } else { status = "unexpected_status"; outcome = "UNKNOWN"; }
            save(new Phase18Raw(1, loaded.planHash(), c.runId(), c.caseId(), Phase18Plan.requestId(c, kind, phase, ordinal), kind, phase, ordinal,
                    clock, offset, sentAt, sentAt, completed, status, reason, code, seq, version, sendState, outcome,
                    outcome.equals("UNKNOWN") ? "unresolved" : "not_applicable", user == null ? null : user.ordinal(), user == null ? null : user.attemptOrdinal(),
                    pixel[0], pixel[1], pixel[2], hash, wire, validation));
            saved=true;
            if(outcome.equals("UNKNOWN")){String id=Phase18Plan.requestId(c,kind,phase,ordinal);unknownRequests.put(id,Map.of("requestId",id,"sendState",sendState,"reason",reason,"lastObservedNanos",completed));}
            if (user != null && !bootstrap) pool.complete(user, completed, outcome.equals("UNKNOWN"));
            if (outcome.equals("UNKNOWN") || !status.equals("accepted")) fail(new IllegalStateException("HTTP terminal requires stop: " + status));
        } catch (Exception | Error invalid) { fail(invalid); }
        finally { if(saved)activeRequests.remove(Phase18Plan.requestId(c,kind,phase,ordinal));slots.release(); control.complete(); }
    }
    private void notSent(String kind, String phase, long ordinal, long offset, String reason, BenchmarkUsers.Assignment user) throws Exception {
        save(new Phase18Raw(1, loaded.planHash(), c.runId(), c.caseId(), Phase18Plan.requestId(c,kind,phase,ordinal),kind,phase,ordinal,clock,offset,
                System.nanoTime(),null,null,"client_not_sent",reason,null,null,null,"not_sent","NOT_SENT","not_applicable",
                user==null?null:user.ordinal(),user==null?null:user.attemptOrdinal(),0,0,0,null,null,null));
    }
    private synchronized void save(Phase18Raw row) throws Exception {
        if (rows.get() >= loaded.plan().counts(c).writes() + loaded.plan().counts(c).reads()) throw new IOException("Terminal ceiling");
        var set=recorded.get(row.kind()+"/"+row.scheduledPhase());int ordinal=Math.toIntExact(row.ordinal());
        if(set.get(ordinal))throw new IOException("Duplicate producer terminal");
        try {raw.write(Phase18Plan.JSON.writeValueAsString(row)); raw.newLine(); raw.flush();set.set(ordinal);rows.incrementAndGet();}
        catch(Exception failure){rawFailed=true;throw failure;}
    }
    private void drain() throws Exception {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(execution.capMillis(c.workload().drainMillis()));
        while (control.snapshot().active() != 0 && System.nanoTime() < until) {execution.check();Thread.sleep(5);}
        if (control.snapshot().active() != 0) throw new TimeoutException("Callback drain incomplete");
        check();
    }
    private void connectWs() throws Exception {
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(execution.capMillis(c.workload().readyMillis()));
        for (int i = 0; i < c.workload().wsConnections(); i++) {
            if(stopped)return;check();
            int connection = i; var closed = new CompletableFuture<Void>(); socketClosed.add(closed);
            sockets.add(http.newWebSocketBuilder().header("Origin","http://localhost:3000").connectTimeout(Duration.ofMillis(c.workload().readyMillis()))
                .buildAsync(URI.create(endpoint.replace("http:","ws:")+"/ws"), new WebSocket.Listener() {
                    private final StringBuilder text = new StringBuilder();
                    public void onOpen(WebSocket s) { wsOpened[connection]=System.nanoTime();s.request(1); }
                    public CompletionStage<?> onText(WebSocket s, CharSequence data, boolean last) {
                        try {
                            if (text.length()+data.length()>2048) throw new IOException("WS message limit");
                            text.append(data);
                            if(last){events.received(connection,Phase18Events.parse(Phase18Plan.JSON.readTree(text.toString())));text.setLength(0);}
                            s.request(1);
                        } catch(Exception|Error error){fail(error); s.sendClose(1002,"invalid");}
                        return null;
                    }
                    public CompletionStage<?> onClose(WebSocket s,int status,String reason){if(!closing||!text.isEmpty())fail(new IOException("Unexpected WS close/partial message"));wsClosedAt[connection]=System.nanoTime();closed.complete(null);return null;}
                    public void onError(WebSocket s,Throwable problem){fail(problem);closed.completeExceptionally(problem);}
                }).get(remaining(until,true),TimeUnit.MILLISECONDS));
        }
    }
    private void awaitWs() throws Exception {
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(execution.capMillis(c.workload().drainMillis()));
        while(System.nanoTime()<until){check();var report=events.report();
            boolean missing=((List<?>)report.get("missing")).stream().anyMatch(n->!n.equals(0L));
            if(!missing){if(Arrays.stream((long[])report.get("duplicates")).sum()!=0||((List<?>)report.get("extra")).stream().anyMatch(n->!n.equals(0L)))throw new IOException("WS set mismatch");return;}Thread.sleep(5);}
        throw new TimeoutException("WS delivery incomplete");
    }
    private void fail(Throwable problem) { failure.compareAndSet(null,problem); stopped=true;control.stop(); }
    private void check() throws Exception { Phase18Process.rethrow(failure.get());execution.check(); }
    private void close() throws Exception {
        closing=true;control.stop();Throwable problem=null;
        try{if(sampler!=null)sampler.close();}catch(Exception|Error e){problem=e;fail(e);}
        try{if(failure.get()!=null)BenchmarkJson.write(output.resolve("producer-failure.json"),failureReport());}catch(Exception|Error e){problem=Phase18Process.preserve(problem,e);}
        try{finishSockets();}catch(Exception|Error e){problem=Phase18Process.preserve(problem,e);}
        try{Phase17CVerificationMain.bounded(()->{
            http.shutdown();while(!http.awaitTermination(Duration.ofSeconds(1))){/* 유한 기한 뒤에도 실제 I/O 소유 유지 */}
            callbacks.shutdown();while(!callbacks.awaitTermination(1,TimeUnit.SECONDS)){/* raw 저장 callback 종료 전 writer 해제 금지 */}
            return true;
        },c.workload().drainMillis(),TimeUnit.MILLISECONDS,"producer-close",()->BenchmarkJson.write(output.resolve("producer-close-timeout.json"),Map.of("nextTaskAllowed",false,"unresolved",unresolved())));
        }catch(Exception|Error e){problem=Phase18Process.preserve(problem,e);}
        try{if(control.snapshot().active()==0&&!rawFailed)fillNotStarted();
        if(failure.get()!=null)BenchmarkJson.write(output.resolve("producer-failure-final.json"),failureReport());}catch(Exception|Error e){problem=Phase18Process.preserve(problem,e);}
        try{if(!Files.exists(output.resolve("ws-canonical.jsonl")))saveWs();}catch(Exception|Error e){problem=Phase18Process.preserve(problem,e);}
        try{raw.close();}catch(Exception|Error e){problem=Phase18Process.preserve(problem,e);}Phase18Process.rethrow(problem);
    }
    private List<Map<String,Object>> unresolved(){var all=new ArrayList<>(activeRequests.values());all.addAll(unknownRequests.values());return List.copyOf(all);}
    private Map<String,Object> failureReport(){
        var report=new LinkedHashMap<String,Object>();report.put("schemaVersion",1);report.put("planHash",loaded.planHash());report.put("clockId",clock);report.put("anchors",anchors);
        report.put("runStatus","INCOMPLETE");report.put("firstFailureType",failure.get().getClass().getName());report.put("planned",loaded.plan().counts(c));report.put("terminalRows",rows.get());
        report.put("unresolved",unresolved());report.put("nextTaskAllowed",false);report.put("ws",events.report());report.put("wsOpenedNanos",wsOpened);report.put("wsClosedNanos",wsClosedAt);
        report.put("control",control.snapshot());report.put("httpTerminated",http.isTerminated());report.put("callbacksTerminated",callbacks.isTerminated());return report;
    }
    private void saveWs()throws Exception {
        try(var writer=Files.newBufferedWriter(output.resolve("ws-canonical.jsonl"),StandardOpenOption.CREATE_NEW)){
            for(int i=0;i<events.size();i++){writer.write(Phase18Plan.JSON.writeValueAsString(events.event(i)));writer.newLine();}
        }
        events.saveReceipts(output.resolve("ws-received.bin"));
    }
    private void finishSockets()throws Exception {
        if(socketsFinished)return;closing=true;
        boolean normal=!stopped&&failure.get()==null;
        long millis=normal?execution.capMillis(c.workload().drainMillis()):c.workload().drainMillis();
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        Throwable problem=null;
        for(int i=0;i<sockets.size();i++)try{sockets.get(i).sendClose(1000,"done").get(remaining(until,normal),TimeUnit.MILLISECONDS);socketClosed.get(i).get(remaining(until,normal),TimeUnit.MILLISECONDS);}
        catch(Exception|Error e){problem=Phase18Process.preserve(problem,e);}
        socketsFinished=problem==null;Phase18Process.rethrow(Phase18Process.preserve(failure.get(),problem));
    }
    private long remaining(long until,boolean normal)throws Exception {
        if(normal)check();
        long millis=TimeUnit.NANOSECONDS.toMillis(until-System.nanoTime());
        if(millis<=0)throw new TimeoutException("WS stage deadline");
        return normal?execution.capMillis(millis):millis;
    }
    /** 실패 뒤 확인된 미시작 namespace만 채움. 송신/저장 불확실 ID는 unresolved에 남기고 NOT_SENT로 바꾸지 않음 */
    private void fillNotStarted()throws Exception {
        for(String phase:List.of("bootstrap","warmup","measurement"))for(String kind:List.of("write","read")) {
            long rate=kind.equals("write")?c.workload().writeRate():c.workload().readRate();
            long count=phase.equals("bootstrap")?(kind.equals("write")&&c.workload().bootstrap()?1:0):rate*(phase.equals("warmup")?c.workload().warmupSeconds():c.workload().measurementSeconds());
            for(int i=0;i<count;i++)if(!recorded.get(kind+"/"+phase).get(i)&&!activeRequests.containsKey(Phase18Plan.requestId(c,kind,phase,i)))
                notSent(kind,phase,i,phase.equals("bootstrap")?0:Phase18Plan.scheduledOffsetNanos(i,rate),"producer_failure",null);
        }
    }
    static int[] pixel(Phase18Plan.Case c,String phase,long ordinal) {
        long n=ordinal+(phase.equals("measurement")?c.workload().writeRate()*c.workload().warmupSeconds():0)+1;
        if(phase.equals("bootstrap"))n=0;
        // 같은 seed/전역 ordinal이 기존 benchmark와 같은 좌표·색을 뜻하도록 원래 규칙에 위임
        return BenchmarkResults.pixel(new BenchmarkSpec.Case("probe",c.workload().writeRate(),
                Math.toIntExact(c.workload().wsConnections()),c.workload().writePattern()),c.workload().seed(),n);
    }
    private Map<String,Object> runtime(){return Map.of("pid",ProcessHandle.current().pid(),"startUtc",ProcessHandle.current().info().startInstant().orElseThrow().toString(),"java",System.getProperty("java.home"),"maxHeap",Runtime.getRuntime().maxMemory(),"usedHeap",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),"temp",System.getProperty("java.io.tmpdir"),"jna",System.getProperty("jna.tmpdir"));}
    private void say(String value){synchronized(protocolOutput){protocolOutput.accept(value);}}
}
