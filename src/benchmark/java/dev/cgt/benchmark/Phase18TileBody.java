package dev.cgt.benchmark;

import java.io.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.LongConsumer;
import java.util.zip.*;

/** wire 수신 중 상한과 단일 production gzip member의 CRC/EOF 검증. raw payload의 증거 저장 금지 */
final class Phase18TileBody {
    static final int WIRE_LIMIT = 131072, RAW_LENGTH = 65536;
    record Verified(long tileVersion, String sha256, int wireBytes, long validationNanos) { }
    static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> done = new CompletableFuture<>();
        private final LongConsumer received;
        private Flow.Subscription subscription;
        Subscriber(int limit) { this(limit,nanos->{}); }
        Subscriber(int limit,LongConsumer received) { Phase18Plan.require(limit > 0 && limit <= WIRE_LIMIT, "body limit"); this.limit = limit; this.received=received; }
        public CompletionStage<byte[]> getBody() { return done; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); done.completeExceptionally(new IOException("Wire body limit")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { done.completeExceptionally(failure); }
        public void onComplete() { byte[] body=bytes.toByteArray();received.accept(System.nanoTime());done.complete(body); }
    }
    static Verified verify(HttpHeaders headers, byte[] wire) throws Exception {
        long start = System.nanoTime();
        for (var expected : Map.of("Content-Type", "application/octet-stream", "Content-Encoding", "gzip", "Cache-Control", "no-store").entrySet())
            Phase18Plan.require(headers.allValues(expected.getKey()).equals(List.of(expected.getValue())), "tile header");
        List<String> versions = headers.allValues("X-Tile-Version");
        Phase18Plan.require(versions.size() == 1 && versions.getFirst().matches("0|[1-9][0-9]{0,18}"), "tile version");
        long version = Long.parseLong(versions.getFirst()); byte[] raw = inflate(wire);
        return new Verified(version, hash(raw), wire.length, System.nanoTime() - start);
    }
    static byte[] inflate(byte[] wire) throws Exception {
        // JDK GZIPOutputStream의 flags=0 단일 member 계약. GZIPInputStream의 trailing garbage 허용에 의존하지 않음
        Phase18Plan.require(wire.length >= 18 && wire.length <= WIRE_LIMIT && (wire[0] & 255) == 31
                && (wire[1] & 255) == 139 && wire[2] == 8 && wire[3] == 0, "gzip header/size");
        byte[] raw = new byte[RAW_LENGTH + 1]; int length = 0;
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(wire, 10, wire.length - 10);
            while (!inflater.finished() && length < raw.length) {
                int n = inflater.inflate(raw, length, raw.length - length); length += n;
                if (n == 0 && !inflater.finished()) throw new IOException("Truncated gzip stream");
            }
            if (length != RAW_LENGTH || !inflater.finished() || inflater.getRemaining() != 8)
                throw new IOException("Gzip length/trailer/extra bytes mismatch");
            int trailer = wire.length - 8; CRC32 crc = new CRC32(); crc.update(raw, 0, length);
            if (little(wire, trailer) != crc.getValue() || little(wire, trailer + 4) != length) throw new IOException("Gzip CRC/ISIZE mismatch");
        } finally { inflater.end(); }
        return Arrays.copyOf(raw, length);
    }
    private static long little(byte[] b, int p) { return (b[p] & 255L) | (b[p+1] & 255L) << 8 | (b[p+2] & 255L) << 16 | (b[p+3] & 255L) << 24; }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    /** production과 같은 생성/write/close/toByteArray 순서의 별도 payload 진단 */
    static byte[] gzip(byte[] raw) throws IOException {
        var bytes = new ByteArrayOutputStream(); try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(raw); } return bytes.toByteArray();
    }
}
