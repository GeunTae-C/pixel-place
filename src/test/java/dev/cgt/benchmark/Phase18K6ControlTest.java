package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** schema4 실제 PS5 HttpClient의 timeout/비200/출력 실패 뒤 STOP와 소유 마감 검증. */
class Phase18K6ControlTest {
 @Test void revisedNativeBridgeTimeoutAndStopKeepOriginalClock()throws Exception {
  Path root=Path.of("F:/pixel-place-work/evidence",System.getenv("PIXEL_PLACE_WORK_SESSION"),"causal-control");Files.createDirectory(root);
  var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);
  var executor=Executors.newFixedThreadPool(2);server.setExecutor(executor);
  var requested=new java.util.concurrent.ConcurrentHashMap<String,Integer>();
  server.createContext("/",exchange->{
   String route=exchange.getRequestURI().getPath();requested.merge(route,1,Integer::sum);
   try{
    exchange.getRequestBody().readAllBytes();
    if(route.equals("/finish"))try{Thread.sleep(400);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    byte[] body="{\"done\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(route.equals("/bad")?503:200,body.length);exchange.getResponseBody().write(body);
   }catch(java.io.IOException cancelled){if(!route.equals("/finish"))throw cancelled;}finally{exchange.close();}
  });server.start();
  try{
   var args=List.of("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe","-NoProfile","-NonInteractive","-File",Path.of("scripts/test-phase18-k6-control.ps1").toAbsolutePath().toString(),"-Output",root.toString(),"-Bridge","http://127.0.0.1:"+server.getAddress().getPort());
   Phase18Process.run(new Phase18Process.Command(args,Path.of(System.getProperty("user.dir")),root.resolve("process"),30000,5000,1048576),child->{});
   assertTrue(BenchmarkJson.read(root.resolve("results.json")).path("Complete").asBoolean());
   assertEquals(Map.of("/ok",1,"/bad",1,"/finish",1,"/stop",1),requested);
   assertTrue(Phase18Process.unresolved().isEmpty());
  }finally{server.stop(0);executor.shutdown();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
 }
}
