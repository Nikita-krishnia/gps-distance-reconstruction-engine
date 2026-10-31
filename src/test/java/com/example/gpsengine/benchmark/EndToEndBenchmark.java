package com.example.gpsengine.benchmark;

import com.example.gpsengine.domain.Role;
import com.example.gpsengine.security.TestSecurityHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Level B End-to-End System Benchmark.
 *
 * <p>Measures end-to-end HTTP request latencies, JSON serialization, Spring Security JWT
 * validation, transaction boundaries, JPA batching, and PostgreSQL disk/table sizes.
 */
@Tag("benchmark")
@EnabledIfEnvironmentVariable(named = "RUN_BENCHMARK", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "routing.provider=fake",
        "routing.cache.enabled=true",
        "recompute.enabled=false"
})
public class EndToEndBenchmark {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private TestSecurityHelper securityHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Run Level B End-to-End Benchmark (Single-threaded and Multi-threaded)")
    void runEndToEndBenchmark() throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromEnv();
        int e2eTrips = config.e2eTrips();
        int e2eThreads = config.e2eThreads();
        int pingsPerTrip = 250;

        System.out.println("================================================================================");
        System.out.println("LEVEL B END-TO-END BENCHMARK INITIALIZATION");
        System.out.printf("Target: %d trips, %d pings/trip, %d client threads, port=%d%n",
                e2eTrips, pingsPerTrip, e2eThreads, port);
        System.out.println("================================================================================");

        String tenantId = "bench-tenant";
        String token = securityHelper.generateToken("bench-operator", tenantId, Role.OPERATOR, null);
        SyntheticTripGenerator generator = new SyntheticTripGenerator(config.seed());

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String baseUrl = "http://localhost:" + port + "/api/v1";

        // 1. Single-client benchmark (warm up and establish baseline)
        System.out.println("Executing baseline E2E run (10 trips, single client)...");
        E2EMetrics baselineMetrics = runE2EBatch(10, 1, pingsPerTrip, generator, client, baseUrl, token);
        System.out.printf("Baseline complete: %.1f trips/s%n", baselineMetrics.tripsPerSec());

        // 2. Full benchmark run with concurrent threads
        System.out.printf("Executing full E2E benchmark (%d trips, %d concurrent client threads)...%n", e2eTrips, e2eThreads);
        E2EMetrics fullMetrics = runE2EBatch(e2eTrips, e2eThreads, pingsPerTrip, generator, client, baseUrl, token);

        System.out.printf("Full E2E Done: Wall=%.2fs, Throughput=%.1f trips/s, Upload=%.0f pings/s%n",
                fullMetrics.wallSeconds(), fullMetrics.tripsPerSec(), fullMetrics.pingsPerSec());

        // 3. Query PostgreSQL table statistics
        List<TableStat> tableStats = queryPostgreSqlStats();

        // 4. Append Level B report to benchmark markdown file
        appendLevelBReport(fullMetrics, tableStats);

        System.out.println("================================================================================");
        System.out.println("LEVEL B BENCHMARK COMPLETE - REPORT APPENDED");
        System.out.println("================================================================================");
    }

    public record LatencyRecord(
            long[] createTripNanos,
            long[] uploadPingsNanos,
            long[] uploadMilestonesNanos,
            long[] calculateNanos,
            long[] getTripNanos,
            long[] getSegmentsNanos
    ) {}

    public record E2EMetrics(
            int trips,
            int threads,
            long totalPings,
            double wallSeconds,
            double tripsPerSec,
            double pingsPerSec,
            BenchmarkMetrics.LatencyPercentiles createTrip,
            BenchmarkMetrics.LatencyPercentiles uploadPings,
            BenchmarkMetrics.LatencyPercentiles uploadMilestones,
            BenchmarkMetrics.LatencyPercentiles calculate,
            BenchmarkMetrics.LatencyPercentiles getTrip,
            BenchmarkMetrics.LatencyPercentiles getSegments
    ) {}

    public record TableStat(
            String tableName,
            long rowCount,
            long totalBytes,
            String prettySize
    ) {}

    private E2EMetrics runE2EBatch(
            int tripsCount,
            int threads,
            int pingsPerTrip,
            SyntheticTripGenerator generator,
            HttpClient client,
            String baseUrl,
            String token) throws Exception {

        long[] createNanos = new long[tripsCount];
        long[] pingsNanos = new long[tripsCount];
        long[] milestonesNanos = new long[tripsCount];
        long[] calcNanos = new long[tripsCount];
        long[] getTripNanos = new long[tripsCount];
        long[] getSegsNanos = new long[tripsCount];

        long totalPingsUploaded = 0;
        long wallStart = System.nanoTime();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(tripsCount);
        AtomicInteger counter = new AtomicInteger(0);

        for (int i = 0; i < tripsCount; i++) {
            executor.submit(() -> {
                int idx = counter.getAndIncrement();
                try {
                    SyntheticTrip syntheticTrip = generator.generateTrip(idx, pingsPerTrip);

                    // A. POST /trips
                    long t0 = System.nanoTime();
                    String createTripBody = objectMapper.writeValueAsString(Map.of(
                            "officePlannedLatitude", syntheticTrip.officePlannedLatitude(),
                            "officePlannedLongitude", syntheticTrip.officePlannedLongitude()
                    ));
                    HttpRequest createReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/trips"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.ofString(createTripBody))
                            .build();
                    HttpResponse<String> createResp = client.send(createReq, HttpResponse.BodyHandlers.ofString());
                    createNanos[idx] = System.nanoTime() - t0;
                    assertThat(createResp.statusCode()).isEqualTo(201);
                    JsonNode tripJson = objectMapper.readTree(createResp.body());
                    long tripId = tripJson.get("id").asLong();

                    // B. POST /trips/{id}/pings
                    long t1 = System.nanoTime();
                    String pingsBody = objectMapper.writeValueAsString(syntheticTrip.toUploadPingsRequest());
                    HttpRequest pingsReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/trips/" + tripId + "/pings"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.ofString(pingsBody))
                            .build();
                    HttpResponse<String> pingsResp = client.send(pingsReq, HttpResponse.BodyHandlers.ofString());
                    pingsNanos[idx] = System.nanoTime() - t1;
                    assertThat(pingsResp.statusCode()).isEqualTo(201);

                    // C. POST /trips/{id}/milestones
                    long t2 = System.nanoTime();
                    String msBody = objectMapper.writeValueAsString(syntheticTrip.toUploadMilestonesRequest());
                    HttpRequest msReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/trips/" + tripId + "/milestones"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.ofString(msBody))
                            .build();
                    HttpResponse<String> msResp = client.send(msReq, HttpResponse.BodyHandlers.ofString());
                    milestonesNanos[idx] = System.nanoTime() - t2;
                    assertThat(msResp.statusCode()).isEqualTo(201);

                    // D. POST /trips/{id}/calculations
                    long t3 = System.nanoTime();
                    HttpRequest calcReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/trips/" + tripId + "/calculations"))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();
                    HttpResponse<String> calcResp = client.send(calcReq, HttpResponse.BodyHandlers.ofString());
                    calcNanos[idx] = System.nanoTime() - t3;
                    assertThat(calcResp.statusCode()).isIn(200, 201);
                    JsonNode calcJson = objectMapper.readTree(calcResp.body());
                    long calcId = calcJson.get("id").asLong();

                    // E. GET /trips/{id}
                    long t4 = System.nanoTime();
                    HttpRequest getTripReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/trips/" + tripId))
                            .header("Authorization", "Bearer " + token)
                            .GET()
                            .build();
                    HttpResponse<String> getTripResp = client.send(getTripReq, HttpResponse.BodyHandlers.ofString());
                    getTripNanos[idx] = System.nanoTime() - t4;
                    assertThat(getTripResp.statusCode()).isEqualTo(200);

                    // F. GET /trips/{id}/calculations/{calcId}/segments
                    long t5 = System.nanoTime();
                    HttpRequest getSegReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/trips/" + tripId + "/calculations/" + calcId + "/segments"))
                            .header("Authorization", "Bearer " + token)
                            .GET()
                            .build();
                    HttpResponse<String> getSegResp = client.send(getSegReq, HttpResponse.BodyHandlers.ofString());
                    getSegsNanos[idx] = System.nanoTime() - t5;
                    assertThat(getSegResp.statusCode()).isEqualTo(200);

                } catch (Throwable e) {
                    System.err.println("E2E worker error on trip " + idx + ": " + e.getMessage());
                    e.printStackTrace();
                    throw new RuntimeException("E2E operation failed on trip index " + idx, e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        long wallNanos = System.nanoTime() - wallStart;
        double wallSec = wallNanos / 1_000_000_000.0;
        totalPingsUploaded = (long) tripsCount * pingsPerTrip;

        return new E2EMetrics(
                tripsCount,
                threads,
                totalPingsUploaded,
                wallSec,
                tripsCount / wallSec,
                totalPingsUploaded / wallSec,
                BenchmarkMetrics.computeLatencyPercentiles(createNanos),
                BenchmarkMetrics.computeLatencyPercentiles(pingsNanos),
                BenchmarkMetrics.computeLatencyPercentiles(milestonesNanos),
                BenchmarkMetrics.computeLatencyPercentiles(calcNanos),
                BenchmarkMetrics.computeLatencyPercentiles(getTripNanos),
                BenchmarkMetrics.computeLatencyPercentiles(getSegsNanos)
        );
    }

    private List<TableStat> queryPostgreSqlStats() {
        List<TableStat> list = new ArrayList<>();
        String[] tables = new String[]{
                "trip", "gps_ping", "trip_milestone", "trip_segment",
                "distance_calculation", "billing_adjustment", "app_user"
        };

        for (String table : tables) {
            try {
                Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
                String prettySize = jdbcTemplate.queryForObject(
                        "SELECT pg_size_pretty(pg_total_relation_size('" + table + "'))", String.class);
                Long bytes = jdbcTemplate.queryForObject(
                        "SELECT pg_total_relation_size('" + table + "')", Long.class);

                list.add(new TableStat(table, count != null ? count : 0L, bytes != null ? bytes : 0L, prettySize != null ? prettySize : "0 bytes"));
            } catch (Exception e) {
                System.err.println("Could not query stats for table " + table + ": " + e.getMessage());
            }
        }
        return list;
    }

    private void appendLevelBReport(E2EMetrics m, List<TableStat> tableStats) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## 6. Level B: End-to-End REST & PostgreSQL System Benchmark\n\n");
        sb.append(String.format("Evaluates the full Spring Boot 3 stack under real HTTP client concurrency (%d client threads, %d complete trips, %,d GPS pings) backed by PostgreSQL 17 via Testcontainers.\n\n",
                m.threads(), m.trips(), m.totalPings()));

        sb.append(String.format("- **Total Wall-Clock Time:** %.2f seconds  \n", m.wallSeconds()));
        sb.append(String.format("- **Completed Trips Throughput:** **%.1f trips/sec**  \n", m.tripsPerSec()));
        sb.append(String.format("- **Ping Ingestion Throughput:** **%,.0f pings/sec**  \n\n", m.pingsPerSec()));

        sb.append("### Endpoint Latency Percentiles\n\n");
        sb.append("| Endpoint / Operation | HTTP Method | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) |\n");
        sb.append("| :--- | :---: | :---: | :---: | :---: | :---: |\n");

        formatEndpointRow(sb, "POST /api/v1/trips (Create Trip)", "POST", m.createTrip());
        formatEndpointRow(sb, "POST /api/v1/trips/{id}/pings (Batch Upload)", "POST", m.uploadPings());
        formatEndpointRow(sb, "POST /api/v1/trips/{id}/milestones (Batch Upload)", "POST", m.uploadMilestones());
        formatEndpointRow(sb, "POST /api/v1/trips/{id}/calculations (Reconstruct Distance)", "POST", m.calculate());
        formatEndpointRow(sb, "GET /api/v1/trips/{id} (Trip Summary)", "GET", m.getTrip());
        formatEndpointRow(sb, "GET /api/v1/trips/{id}/calculations/{calcId}/segments (Audit Trail)", "GET", m.getSegments());

        sb.append("\n### PostgreSQL Table Storage & Row Counts\n\n");
        sb.append("| Database Table | Row Count | Disk Size (Total Relation) |\n");
        sb.append("| :--- | :---: | :---: |\n");
        for (TableStat ts : tableStats) {
            sb.append(String.format("| `%s` | %,d | %s |\n", ts.tableName(), ts.rowCount(), ts.prettySize()));
        }
        sb.append("\n");

        // Write to both latest and find most recent timestamped results file
        try {
            File dir = new File("docs/benchmarks");
            File latest = new File(dir, "results-latest.md");
            if (latest.exists()) {
                try (FileWriter fw = new FileWriter(latest, true)) {
                    fw.write(sb.toString());
                }
            }

            // Also find latest timestamped file
            File[] files = dir.listFiles((d, name) -> name.startsWith("results-20") && name.endsWith(".md"));
            if (files != null && files.length > 0) {
                Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
                try (FileWriter fw = new FileWriter(files[0], true)) {
                    fw.write(sb.toString());
                }
            }
        } catch (Exception e) {
            System.err.println("Failed appending Level B report: " + e.getMessage());
        }
    }

    private void formatEndpointRow(StringBuilder sb, String name, String method, BenchmarkMetrics.LatencyPercentiles p) {
        sb.append(String.format("| %s | %s | %.2f ms | %.2f ms | %.2f ms | %.2f ms |\n",
                name, method, p.p50Micros() / 1000.0, p.p95Micros() / 1000.0, p.p99Micros() / 1000.0, p.maxMicros() / 1000.0));
    }
}
