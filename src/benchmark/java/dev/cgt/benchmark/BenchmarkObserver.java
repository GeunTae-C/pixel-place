package dev.cgt.benchmark;

import dev.cgt.pixelplace.flush.application.PendingAmbiguousFlushStore;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.pixel.application.PixelWriteExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 유한 주기의 JVM·registry·파일 metadata 관측. WAL scan·dirty drain·forced flush 호출 없음 */
final class BenchmarkObserver implements AutoCloseable {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "benchmark-observer"); thread.setDaemon(true); return thread;
    });
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private final PixelMeasurement measurement;
    private final PixelWriteExecutor writeExecutor;
    private final MeterRegistry registry;
    private final PendingAmbiguousFlushStore pending;
    private final BenchmarkServletConfiguration.Activity activity;
    private final Path wal;
    private final int maximumSamples, closeSeconds;
    private final JvmResources resources = new JvmResources();
    private long previousCapture = Long.MIN_VALUE;
    private volatile String failure;
    private boolean stopped;

    BenchmarkObserver(PixelMeasurement measurement, MeterRegistry registry, PendingAmbiguousFlushStore pending,
                      BenchmarkServletConfiguration.Activity activity, PixelWriteExecutor writeExecutor, Path wal, BenchmarkSpec spec, BenchmarkSpec.Plan plan) {
        this.measurement = measurement; this.registry = registry; this.pending = pending; this.activity = activity; this.wal = wal;
        this.writeExecutor = writeExecutor;
        maximumSamples = plan.maximumSamples(); closeSeconds = spec.controlTimeoutSeconds();
        executor.scheduleWithFixedDelay(this::sample, 0, spec.sampleIntervalMillis(), TimeUnit.MILLISECONDS);
    }
    private synchronized void sample() {
        try {
            if (samples.size() >= maximumSamples) throw new IllegalStateException("sample allocation limit");
            var row = new LinkedHashMap<>(resources.sample());
            row.put("phase", measurement.phase().name()); row.put("commandActive", measurement.activeCommands());
            row.put("executor", writeExecutor.snapshot()); row.put("walCounts", measurement.walCounts());
            row.put("lastCycle", measurement.lastCycle());
            row.put("servletActive", activity.active.get()); row.put("servletStarted", activity.started.get()); row.put("servletCompleted", activity.completed.get());
            boolean hasPending = pending.current().isPresent(); row.put("pending", hasPending);
            var capture = measurement.lastCapture();
            row.put("capture", capture); row.put("captureAgeNanos", capture == null ? null : Math.max(0, System.nanoTime() - capture.observedNanos()));
            row.put("captureStatus", captureStatus(capture, previousCapture, hasPending));
            if (capture != null) previousCapture = capture.observedNanos();
            row.put("wal", walMetadata(wal)); row.put("timers", timers(registry));
            row.put("observerFailures", measurement.observerFailures());
            row.put("broadcastFailures", measurement.enabled() ? measurement.broadcastFailures() : null);
            samples.add(row);
        } catch (RuntimeException problem) { failure = problem.getClass().getSimpleName(); }
        catch (Error problem) { failure = problem.getClass().getSimpleName(); throw problem; }
    }
    static String captureStatus(PixelMeasurement.Capture capture, long previous, boolean pending) {
        if (pending) return "pending-gap";
        if (capture == null) return "not-observed";
        return capture.observedNanos() == previous ? "stale" : "fresh";
    }
    static Map<String, Object> walMetadata(Path wal) {
        try (var stream = Files.list(wal.getParent())) {
            long bytes = 0; int count = 0;
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.equals(wal.getFileName().toString()) || name.matches(java.util.regex.Pattern.quote(wal.getFileName().toString()) + "\\.seg-[0-9]{19}")) {
                    bytes = Math.addExact(bytes, Files.size(path)); count++;
                }
            }
            return Map.of("status", "observed", "bytes", bytes, "segments", count);
        } catch (Exception unavailable) {
            // 삭제 경합은 missing 표본이며 storage 실패·원본 보정 사유가 아님
            return Map.of("status", "missing", "reason", unavailable.getClass().getSimpleName());
        }
    }
    static List<Map<String, Object>> timers(MeterRegistry registry) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (var meter : registry.getMeters()) if (meter instanceof Timer timer && meter.getId().getName().equals("pixel.place.operation") && timer.count() > 0) {
            var row = new LinkedHashMap<String, Object>();
            row.put("operation", timer.getId().getTag("operation")); row.put("phase", timer.getId().getTag("phase"));
            row.put("outcome", timer.getId().getTag("outcome")); row.put("count", timer.count());
            row.put("totalMillis", timer.totalTime(TimeUnit.MILLISECONDS)); row.put("windowMaxMillis", timer.max(TimeUnit.MILLISECONDS));
            values.add(row);
        }
        return values;
    }
    void write(Path directory) throws Exception {
        if (!stopped) throw new IllegalStateException("Sampler must stop before collect");
        BenchmarkJson.write(directory.resolve("resource-samples.json"), Map.of("server", samples, "cpuUnit", "process CPU nanos delta / elapsed nanos; one core = 1.0; not host normalized", "gcUnit", "JVM cumulative collection count and milliseconds; delta fields are consecutive sample differences"));
        var metrics = new LinkedHashMap<String, Object>(); metrics.put("enabled", measurement.enabled()); metrics.put("incomplete", failure != null || measurement.observerFailures() != 0);
        metrics.put("samplerFailure", failure); metrics.put("observerFailures", measurement.observerFailures()); metrics.put("timers", timers(registry));
        metrics.put("walCounts", measurement.walCounts());
        metrics.put("performanceCollection", measurement.enabled() ? "collected" : "disabled: operation timers and batch-size distribution");
        metrics.put("disabledFields", measurement.enabled() ? Map.of() : Map.of(
                "timers", "instrumentation off", "batchSizes", "instrumentation off",
                "lastCycle", "instrumentation off", "broadcastFailures", "instrumentation off"));
        metrics.put("units", Map.of("command/group_request/core_call/core_inside", "request",
                "batch_core_call/batch_core_inside/write_wait/write_held", "executor invocation (single request or group batch)",
                "queue_wait", "claimed request enqueue-to-claim", "record_force/empty_force", "actual file force call",
                "batchSizes", "actual appendBatch record count; singleton included; bucket 129 means above 128"));
        metrics.put("timerWindow", "expiry 60s x 3 buffers; count/total cumulative, max rolling; HTTP percentiles use raw client nearest-rank");
        BenchmarkJson.write(directory.resolve("server-metrics.json"), metrics);
    }
    boolean complete() { return failure == null && measurement.observerFailures() == 0; }
    @Override public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(closeSeconds, TimeUnit.SECONDS)) throw new IllegalStateException("Observer thread still alive");
            stopped = true;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Observer stop interrupted"); }
    }

    /** 서버/발생기가 각각 자기 PID와 단조 시계에서 계산하는 CPU·heap·GC 표본 */
    static final class JvmResources {
        private long previousTime, previousCpu, previousGcCount, previousGcMillis;
        Map<String, Object> sample() {
            long now = System.nanoTime();
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long cpu = os.getProcessCpuTime(), count = 0, millis = 0;
            for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) { count += Math.max(0, gc.getCollectionCount()); millis += Math.max(0, gc.getCollectionTime()); }
            var result = new LinkedHashMap<String, Object>();
            result.put("nanoTime", now); result.put("utc", java.time.Instant.now().toString()); result.put("pid", ProcessHandle.current().pid());
            result.put("cpuNanos", cpu); result.put("cpuCoresUsed", previousTime == 0 || cpu < 0 ? null : (double) (cpu - previousCpu) / (now - previousTime));
            result.put("heapUsed", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            result.put("heapMax", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
            result.put("gcCount", count); result.put("gcMillis", millis);
            result.put("gcCountDelta", previousTime == 0 ? null : count - previousGcCount); result.put("gcMillisDelta", previousTime == 0 ? null : millis - previousGcMillis);
            result.put("hostFreeMemory", os.getFreeMemorySize()); result.put("hostTotalMemory", os.getTotalMemorySize());
            previousTime = now; previousCpu = cpu; previousGcCount = count; previousGcMillis = millis;
            return result;
        }
    }
}
