package com.example.gpsengine.benchmark;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metric collector and hardware resource sampler for benchmarking.
 */
public class BenchmarkMetrics {

    public record ScenarioSummary(
            String scenarioId,
            String description,
            int trips,
            long rawPings,
            double wallClockSeconds,
            double pipelineSeconds,
            double tripsPerSecond,
            double pingsPerSecond,
            double p50Micros,
            double p95Micros,
            double p99Micros,
            double maxMicros,
            long peakHeapBytes,
            long maxHeapBytes,
            long gcCollections,
            long gcTimeMillis,
            double cpuLoadPercent,
            long totalProviderCalls,
            double avgCallsPerTrip,
            int maxCallsPerTrip,
            long cacheHits,
            long cacheMisses,
            long cacheBypasses,
            double hitRatePercent,
            int calculatedCount,
            int degradedCount,
            long totalMeters,
            String checksum
    ) {}

    public static class MemorySampler implements AutoCloseable {
        private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        private final AtomicLong peakUsed = new AtomicLong(0);
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread samplerThread;

        public MemorySampler() {
            this.samplerThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        long used = memoryBean.getHeapMemoryUsage().getUsed();
                        peakUsed.accumulateAndGet(used, Math::max);
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }, "benchmark-memory-sampler");
            this.samplerThread.setDaemon(true);
            this.samplerThread.start();
        }

        public long getPeakUsedHeap() {
            return peakUsed.get();
        }

        @Override
        public void close() {
            running.set(false);
            samplerThread.interrupt();
            try {
                samplerThread.join(200);
            } catch (InterruptedException ignored) {}
        }
    }

    public static long getGcCount() {
        long sum = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0) sum += count;
        }
        return sum;
    }

    public static long getGcTimeMillis() {
        long sum = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = bean.getCollectionTime();
            if (time > 0) sum += time;
        }
        return sum;
    }

    public static double getProcessCpuLoad() {
        try {
            var osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double load = sunBean.getProcessCpuLoad();
                if (load >= 0) return load * 100.0;
            }
        } catch (Throwable ignored) {}
        return -1.0;
    }

    public static String computeChecksum(List<EnginePipeline.PipelineResult> results) {
        // Sort by tripIndex to ensure determinism independent of parallel thread completion order
        EnginePipeline.PipelineResult[] array = results.toArray(new EnginePipeline.PipelineResult[0]);
        Arrays.sort(array, (a, b) -> Integer.compare(a.tripIndex(), b.tripIndex()));

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(16);
            for (EnginePipeline.PipelineResult r : array) {
                buffer.clear();
                buffer.putLong(r.tripIndex());
                buffer.putLong(r.totalMeters());
                md.update(buffer.array());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static LatencyPercentiles computeLatencyPercentiles(long[] pipelineNanosArray) {
        Arrays.sort(pipelineNanosArray);
        int n = pipelineNanosArray.length;
        if (n == 0) {
            return new LatencyPercentiles(0, 0, 0, 0);
        }
        double p50 = pipelineNanosArray[(int) (n * 0.50)] / 1000.0;
        double p95 = pipelineNanosArray[(int) (n * 0.95)] / 1000.0;
        double p99 = pipelineNanosArray[(int) (n * 0.99)] / 1000.0;
        double max = pipelineNanosArray[n - 1] / 1000.0;
        return new LatencyPercentiles(p50, p95, p99, max);
    }

    public record LatencyPercentiles(double p50Micros, double p95Micros, double p99Micros, double maxMicros) {}
}

