package com.example.gpsengine.benchmark;

/**
 * Configuration settings and environment variable bindings for benchmark runs.
 */
public record BenchmarkConfig(
        int trips,
        int pings,
        int threads,
        int places,
        int repeats,
        int e2eTrips,
        int e2eThreads,
        int simLatencyMs,
        int simTrips,
        long seed
) {
    public static final int DEFAULT_TRIPS = 10_000;
    public static final int DEFAULT_PINGS = 1_500;
    public static final int DEFAULT_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors());
    public static final int DEFAULT_PLACES = 150;
    public static final int DEFAULT_REPEATS = 3;
    public static final int DEFAULT_E2E_TRIPS = 200;
    public static final int DEFAULT_E2E_THREADS = 4;
    public static final int DEFAULT_SIM_LATENCY_MS = 20;
    public static final int DEFAULT_SIM_TRIPS = 200;
    public static final long DEFAULT_SEED = 42L;

    public static BenchmarkConfig fromEnv() {
        int trips = getEnvInt("BENCH_TRIPS", DEFAULT_TRIPS);
        int pings = getEnvInt("BENCH_PINGS", DEFAULT_PINGS);
        int threads = getEnvInt("BENCH_THREADS", DEFAULT_THREADS);
        int places = getEnvInt("BENCH_PLACES", DEFAULT_PLACES);
        int repeats = getEnvInt("BENCH_REPEATS", DEFAULT_REPEATS);
        int e2eTrips = getEnvInt("BENCH_E2E_TRIPS", DEFAULT_E2E_TRIPS);
        int e2eThreads = getEnvInt("BENCH_E2E_THREADS", DEFAULT_E2E_THREADS);
        int simLatencyMs = getEnvInt("BENCH_SIM_LATENCY_MS", DEFAULT_SIM_LATENCY_MS);
        int simTrips = getEnvInt("BENCH_SIM_TRIPS", DEFAULT_SIM_TRIPS);
        long seed = getEnvLong("BENCH_SEED", DEFAULT_SEED);

        return new BenchmarkConfig(
                trips,
                pings,
                threads,
                places,
                repeats,
                e2eTrips,
                e2eThreads,
                simLatencyMs,
                simTrips,
                seed
        );
    }

    private static int getEnvInt(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            val = System.getProperty(key);
        }
        if (val != null && !val.isBlank()) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static long getEnvLong(String key, long defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            val = System.getProperty(key);
        }
        if (val != null && !val.isBlank()) {
            try {
                return Long.parseLong(val.trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}

