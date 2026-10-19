package dev.cgt.benchmark;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** 실제 k6 검증용 유한 HTTP/WS 대역. pixel-place·DB·Redis·WAL의 동작을 대신 검증하지 않음 */
final class Phase18K6FixtureServer implements AutoCloseable {
    private final ServerSocket listener=new ServerSocket(0,256,InetAddress.getByName("127.0.0.1"));
    private final ExecutorService workers=Executors.newFixedThreadPool(232);
    private final List<Socket> sockets=new CopyOnWriteArrayList<>(),websockets=new CopyOnWriteArrayList<>();
    private final ServiceJwtTokens tokens;
    private final Map<Integer,byte[]> tiles=new HashMap<>();
    private final Map<Integer,Long> versions=new HashMap<>();
    final List<Map<String,Object>> observed=Collections.synchronizedList(new ArrayList<>());
    final List<Throwable> failures=new CopyOnWriteArrayList<>();
    private final Thread accept;
    private volatile boolean closed;
    private long sequence;
    private final boolean duplicateWs,connectionBursts;
    private final BlockingQueue<byte[]> burstFrames=new ArrayBlockingQueue<>(5000);
    private final Thread broadcaster;
    private final java.util.function.LongConsumer beforeWriteReply;
    Phase18K6FixtureServer(ServiceJwtTokens tokens)throws Exception {
        this(tokens,false);
    }
    Phase18K6FixtureServer(ServiceJwtTokens tokens,boolean duplicateWs)throws Exception {
        this(tokens,duplicateWs,false);
    }
    Phase18K6FixtureServer(ServiceJwtTokens tokens,boolean duplicateWs,boolean connectionBursts)throws Exception {
        this(tokens,duplicateWs,connectionBursts,null);
    }
    // STOP 시 이미 완료한 효과와 늦은 HTTP 응답을 구분하는 격리 fixture hook
    Phase18K6FixtureServer(ServiceJwtTokens tokens,boolean duplicateWs,boolean connectionBursts,java.util.function.LongConsumer beforeWriteReply)throws Exception {
        this.beforeWriteReply=beforeWriteReply;
        this.tokens=tokens;this.duplicateWs=duplicateWs;this.connectionBursts=connectionBursts;
        if(duplicateWs&&connectionBursts)throw new IllegalArgumentException("separate fixture cases");
        broadcaster=connectionBursts?Thread.ofPlatform().name("k6-fixture-ws-burst").start(this::broadcastBursts):null;
        accept=Thread.ofPlatform().name("k6-fixture-accept").start(()->{
            while(!closed)try{var socket=listener.accept();socket.setSoTimeout(180000);socket.setTcpNoDelay(true);sockets.add(socket);workers.submit(()->handle(socket));}
            catch(IOException e){if(!closed)failures.add(e);}
        });
    }
    String endpoint(){return "http://127.0.0.1:"+listener.getLocalPort();}
    private static String line(InputStream in)throws IOException {
        var bytes=new ByteArrayOutputStream();int b;
        while((b=in.read())!=-1&&b!='\n'){if(b!='\r')bytes.write(b);if(bytes.size()>8192)throw new IOException("fixture header limit");}
        return bytes.toString(StandardCharsets.US_ASCII);
    }
    private void handle(Socket socket){
        try(socket){
            var in=new BufferedInputStream(socket.getInputStream());int completed=0;
            while(!closed){
            // 완료 응답 뒤 다음 요청의 첫 byte 대기 중 peer reset은 유휴 연결 종료.
            // 첫 요청/부분 header/body/응답/WS 처리 중 오류는 기존 실패 경로 유지
            in.mark(1);int firstByte;
            try{firstByte=in.read();}catch(SocketException e){if(completed>0)return;throw e;}
            if(firstByte<0)return;in.reset();
            String first=line(in);if(first.isEmpty())return;
            String[] request=first.split(" ");var headers=new HashMap<String,String>();String row;
            while(!(row=line(in)).isEmpty()){int i=row.indexOf(':');if(i<=0)throw new IOException("fixture header");headers.put(row.substring(0,i).toLowerCase(Locale.ROOT),row.substring(i+1).trim());if(headers.size()>32)throw new IOException("fixture header count");}
            int length=Integer.parseInt(headers.getOrDefault("content-length","0"));if(length>8192)throw new IOException("fixture body limit");byte[] body=in.readNBytes(length);
            if(request[1].equals("/ws")) {
                String key=headers.get("sec-websocket-key");String digest=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+digest+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();websockets.add(socket);
                int one,two;
                while((one=in.read())!=-1&&(two=in.read())!=-1){int size=two&127;if(size==126)size=(in.read()<<8)|in.read();if(size>4096)throw new IOException("fixture WS frame");byte[] mask=(two&128)!=0?in.readNBytes(4):new byte[0];byte[] payload=in.readNBytes(size);if(mask.length>0)for(int i=0;i<size;i++)payload[i]^=mask[i%4];if((one&15)==8){frame(socket,8,payload);break;}}
                websockets.remove(socket);return;
            }
            byte[] deferredResponse=null;long deferredSequence=0;
            synchronized(this){
                if(request[1].equals("/api/pixels")){
                    String auth=headers.getOrDefault("authorization","");tokens.accessDecoder().decode(auth.substring(7));
                    var data=Phase18Plan.JSON.readTree(body);int x=data.path("x").asInt(),y=data.path("y").asInt(),color=data.path("color").asInt();int tile=y/256*32+x/256;
                    byte[] raw=tiles.computeIfAbsent(tile,t->{return dev.cgt.pixelplace.tile.domain.TileState.allWhite().pixels();});raw[(y%256)*256+x%256]=(byte)color;
                    long version=versions.merge(tile,1L,Long::sum),seq=++sequence;
                    var event=Map.<String,Object>of("type","pixel","eventSeq",seq,"tileVersion",version,"x",x,"y",y,"color",color);
                    // 같은 100개 전송의 frame은 한 번 직렬화. 각 연결 쓰기는 단일 유한 byte 배열
                    byte[] encoded=encodedFrame(1,Phase18Plan.JSON.writeValueAsBytes(event)),changedFrame=null;
                    if(duplicateWs){var changed=new HashMap<>(event);changed.put("color",(color+1)%256);changedFrame=encodedFrame(1,Phase18Plan.JSON.writeValueAsBytes(changed));}
                    if(connectionBursts){
                        if(!websockets.isEmpty()&&!burstFrames.offer(encoded,10,TimeUnit.SECONDS))throw new IOException("bounded fixture burst queue");
                    }else for(var ws:websockets){sendFrame(ws,encoded);if(duplicateWs)sendFrame(ws,changedFrame);}
                    byte[] response=Phase18Plan.JSON.writeValueAsBytes(Map.of("accepted",true,"eventSeq",seq,"tileVersion",version,"x",x,"y",y,"color",color));
                    observed.add(Map.of("kind","write","id",headers.get("x-phase18-request"),"event",event));
                    if(beforeWriteReply==null)reply(socket,response,Map.of("Content-Type","application/json"));
                    else {deferredResponse=response;deferredSequence=seq;}
                }else if(request[1].startsWith("/api/tiles/0/")){
                    String[] parts=request[1].split("/");int tile=Integer.parseInt(parts[5])*32+Integer.parseInt(parts[4]);
                    byte[] raw=tiles.computeIfAbsent(tile,t->{return dev.cgt.pixelplace.tile.domain.TileState.allWhite().pixels();});long version=versions.getOrDefault(tile,0L);
                    reply(socket,Phase18TileBody.gzip(raw),Map.of("Content-Type","application/octet-stream","Content-Encoding","gzip","Cache-Control","no-store","X-Tile-Version",Long.toString(version)));
                    observed.add(Map.of("kind","read","id",headers.get("x-phase18-request"),"hash",Phase18TileBody.hash(raw),"version",version));
                }else throw new IOException("undefined fixture request");
                completed++;
            }
            if(deferredResponse!=null){beforeWriteReply.accept(deferredSequence);reply(socket,deferredResponse,Map.of("Content-Type","application/json"));}
            }
        }catch(Exception e){if(!closed)failures.add(e);}finally{sockets.remove(socket);websockets.remove(socket);}
    }
    private static void reply(Socket socket,byte[] body,Map<String,String> headers)throws IOException{
        var text=new StringBuilder("HTTP/1.1 200 OK\r\nConnection: keep-alive\r\nContent-Length: ").append(body.length).append("\r\n");headers.forEach((k,v)->text.append(k).append(": ").append(v).append("\r\n"));text.append("\r\n");
        socket.getOutputStream().write(text.toString().getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();
    }
    private static void frame(Socket socket,int opcode,byte[] data)throws IOException{
        sendFrame(socket,encodedFrame(opcode,data));
    }
    private static byte[] encodedFrame(int opcode,byte[] data)throws IOException{
        var out=new ByteArrayOutputStream(data.length+4);out.write(128|opcode);
        if(data.length<126)out.write(data.length);else{out.write(126);out.write(data.length>>>8);out.write(data.length&255);}
        out.write(data);return out.toByteArray();
    }
    private static void sendFrame(Socket socket,byte[] encoded)throws IOException{
        synchronized(socket){socket.getOutputStream().write(encoded);}
    }
    // production에서 관측한 연결별 callback 집중을 격리 재현. 5000 frame/8MiB·최대5초의 유한 burst
    private void broadcastBursts(){
        try{
            while(!closed){
                byte[] first=burstFrames.poll(100,TimeUnit.MILLISECONDS);if(first==null)continue;
                var bytes=new ByteArrayOutputStream();bytes.write(first);int count=1;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!closed&&count<5000&&System.nanoTime()<until){
                    byte[] next=burstFrames.poll(Math.max(0,Math.min(TimeUnit.NANOSECONDS.toMillis(until-System.nanoTime())+1,100)),TimeUnit.MILLISECONDS);
                    if(next!=null){if(bytes.size()+next.length>8*1024*1024)throw new IOException("fixture burst bytes");bytes.write(next);count++;}
                }
                if(closed)return;byte[] encoded=bytes.toByteArray();
                for(var socket:websockets)sendFrame(socket,encoded);
            }
        }catch(Exception e){if(!closed)failures.add(e);}
    }
    public void close()throws Exception{
        closed=true;listener.close();for(var socket:sockets)socket.close();accept.join(2000);
        if(broadcaster!=null){broadcaster.interrupt();broadcaster.join(3000);if(broadcaster.isAlive())throw new IllegalStateException("fixture broadcaster unresolved");}
        workers.shutdown();if(!workers.awaitTermination(3,TimeUnit.SECONDS))throw new IllegalStateException("fixture workers unresolved");
    }
}
