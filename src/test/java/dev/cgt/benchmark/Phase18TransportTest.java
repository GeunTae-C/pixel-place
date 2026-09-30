package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

/** gzip 변조·동시 WS ordinal·사용자 수명 검증. 정상 입력 수용과 실제 경계 거부를 함께 고정 */
class Phase18TransportTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private final Phase18Events.Event a = new Phase18Events.Event(9007199254740993L, 1, 0, 0, 17);
    private HttpHeaders headers(String version) { return HttpHeaders.of(Map.of("Content-Type", List.of("application/octet-stream"),
            "Content-Encoding", List.of("gzip"), "Cache-Control", List.of("no-store"), "X-Tile-Version", List.of(version)), (k,v) -> true); }

    @Test void gzipAcceptsBothPayloadsAndRejectsTruncationCrcTrailingLengthAndVersion() throws Exception {
        byte[] raw = new byte[65536]; new Random(18002).nextBytes(raw);
        for (byte[] input : List.of(raw, new byte[65536])) {
            byte[] gzip = Phase18TileBody.gzip(input);
            assertEquals(Phase18TileBody.hash(input), Phase18TileBody.verify(headers("17"), gzip).sha256());
            assertArrayEquals(input, Phase18TileBody.inflate(gzip));
            assertThrows(Exception.class, () -> Phase18TileBody.inflate(Arrays.copyOf(gzip, gzip.length - 1)));
            assertThrows(Exception.class, () -> Phase18TileBody.inflate(Arrays.copyOf(gzip, gzip.length + 1)));
            byte[] badCrc = gzip.clone(); badCrc[badCrc.length - 8] ^= 1;
            assertThrows(Exception.class, () -> Phase18TileBody.inflate(badCrc));
            for (String v : List.of("-1", "1.0", "01", "9223372036854775808", ""))
                assertThrows(Exception.class, () -> Phase18TileBody.verify(headers(v), gzip));
        }
        for (int size : List.of(65535, 65537)) assertThrows(Exception.class, () -> Phase18TileBody.inflate(Phase18TileBody.gzip(new byte[size])));
    }
    @Test void bodySubscriberRejectsWhileReceivingAndDoesNotRetainExcess() {
        var body = new Phase18TileBody.Subscriber(4); boolean[] cancelled = {false};
        body.onSubscribe(new Flow.Subscription() { public void request(long n) { } public void cancel() { cancelled[0] = true; } });
        body.onNext(List.of(ByteBuffer.wrap(new byte[3]))); assertFalse(body.getBody().toCompletableFuture().isDone());
        body.onNext(List.of(ByteBuffer.wrap(new byte[2])));
        assertTrue(cancelled[0]); assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
    }
    @Test void bodyReceiveClockIsFixedBeforeConsumerValidationAndRecording() {
        var order=new ArrayList<String>();var stamp=new java.util.concurrent.atomic.AtomicLong();
        var body=new Phase18TileBody.Subscriber(4,nanos->{stamp.set(nanos);order.add("received");});
        body.getBody().thenAccept(bytes->{assertNotEquals(0,stamp.get());order.add("validation-and-recording");});
        body.onComplete();assertEquals(List.of("received","validation-and-recording"),order);
    }
    @Test void concurrentFirstWsAndHttpGetOneOrdinalAndExactPerConnectionSets() throws Exception {
        var store = new Phase18Events(2, 32); var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(33)) {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 32; i++) { int c = i; jobs.add(workers.submit(() -> { start.await(); store.received(c, a); return null; })); }
            jobs.add(workers.submit(() -> { start.await(); store.accepted(a); return null; })); start.countDown();
            for (var j : jobs) j.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, store.size()); assertEquals(Collections.nCopies(32, 0L), store.report().get("missing"));
        store.received(0, a); assertEquals(1, ((long[]) store.report().get("duplicates"))[0]);
        assertThrows(IllegalStateException.class, () -> store.received(1, new Phase18Events.Event(a.eventSeq(), 2, 0, 0, 17)));
    }
    @Test void reverseOrderMissingExtraAndUniqueCeilingRemainIndependent() {
        var store = new Phase18Events(2, 2); var b = new Phase18Events.Event(10, 2, 0, 0, 23);
        store.received(0, a); store.received(0, b); store.accepted(b); store.accepted(a); store.received(1, b);
        assertEquals(List.of(0L, 1L), store.report().get("missing"));
        assertThrows(IllegalStateException.class, () -> store.accepted(new Phase18Events.Event(12, 3, 0, 0, 30)));
        var unknown = new Phase18Events(1, 1); unknown.received(0, a);
        assertEquals(List.of(1L), unknown.report().get("extra"));
    }
    @Test void wsParsingRetainsLongPrecisionAndRejectsFractionTypesAndRange() {
        String json = "{\"type\":\"pixel\",\"x\":0,\"y\":0,\"color\":17,\"eventSeq\":9007199254740993,\"tileVersion\":1}";
        assertEquals(a, Phase18Events.parse(Phase18Plan.JSON.readTree(json)));
        for (String bad : List.of(json.replace("9007199254740993", "1.0"), json.replace("\"x\":0", "\"x\":8192"), json.replace("\"color\":17", "\"color\":4294967313")))
            assertThrows(IllegalArgumentException.class, () -> Phase18Events.parse(Phase18Plan.JSON.readTree(bad)));
    }
    @Test void actualPrimitiveRetentionFitsV2RepresentativeAndSoakReservation() {
        for (int count : List.of(15750, 192150, 200000)) {
            var store = new Phase18Events(count, 100);
            assertTrue(store.retainedArrayBytes() <= count * 128L + ((count + 63L) / 64) * 800 + 25600);
        }
    }
    @Test void persistedConnectionBitsetsRetainOutOfOrderMembershipAndMissingIdentity() throws Exception {
        var store=new Phase18Events(65,2);var b=new Phase18Events.Event(10,2,0,0,23);
        store.received(0,a);store.received(1,b);store.accepted(a);store.accepted(b);
        var file=directory.resolve("received.bin");store.saveReceipts(file);
        assertEquals(32,java.nio.file.Files.size(file));
        try(var in=new java.io.DataInputStream(java.nio.file.Files.newInputStream(file))){assertEquals(1L,in.readLong());assertEquals(0L,in.readLong());assertEquals(2L,in.readLong());assertEquals(0L,in.readLong());assertEquals(-1,in.read());}
    }
    @Test void poolWithoutBootstrapUsesFirstUserAndPreservesBusyTtlUnknownAndOnce() {
        var users = new BenchmarkUsers(2, false, 1000, false);
        var a = users.acquire(0); var b = users.acquire(0); assertEquals(0, a.ordinal()); assertNull(users.acquire(0));
        users.complete(a, 100, false); users.complete(b, 100, true);
        assertNull(users.acquire(180_999_999_999L)); assertNull(users.acquire(181_000_000_099L));
        var next = users.acquire(181_000_000_100L); assertEquals(a.ordinal(), next.ordinal()); assertEquals(2, next.attemptOrdinal());
        assertNull(users.acquire(Long.MAX_VALUE));
        var once = new BenchmarkUsers(1, true, 1000, false); var only = once.acquire(0); once.complete(only, 1, false);
        assertNull(once.acquire(Long.MAX_VALUE)); assertNull(new BenchmarkUsers(0, true, 1000, false).acquire(0));
    }
}
