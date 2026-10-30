# GPS Reconstruction Engine - Benchmarking Guide

This document describes how to execute, configure, and interpret the performance and scalability benchmarks for the GPS Distance Reconstruction Engine.

---

## 1. Overview & Architecture

The benchmark suite is divided into two distinct measurement levels:

- **Level A (Engine Benchmark - `EngineBenchmark.java`):**
  - **Focus:** Pure-Java throughput, latency percentiles, heap memory usage, GC pressure, and concurrency scaling.
  - **Scope:** Bypasses Spring context, HTTP serialization, and database persistence to measure the raw computational limits of the core reconstruction pipeline (`GpsCleaner` -> `AnchorBuilder` -> `TripTimelineMerger` -> `TimelineFingerprint` -> `DistanceEngine`).
  - **Tooling:** Measures with standard JDK APIs (`System.nanoTime`, `MemoryMXBean`, `GarbageCollectorMXBean`, `OperatingSystemMXBean`) without external dependencies or JMH.

- **Level B (End-to-End System Benchmark - `EndToEndBenchmark.java`):**
  - **Focus:** Complete application stack performance under real HTTP client concurrency.
  - **Scope:** Exercises JSON serialization, Spring Security JWT authentication, transaction boundaries, JPA batch inserts, and PostgreSQL 17 disk storage via Testcontainers.
  - **Tooling:** Uses JDK 11+ `java.net.http.HttpClient` driving concurrent REST requests against a live Spring Boot server (`RANDOM_PORT`).

- **Regression Guard (`BenchmarkRegressionGuardTest.java`):**
  - Small `@Tag("unit")` test running 20 synthetic trips in standard builds to ensure zero regressions in determinism, invariant compliance, and generator sanity without slowing down normal CI cycles.

---

## 2. Configuration & Environment Variables

All benchmarks read configuration dynamically from environment variables, falling back to production-grade defaults:

| Variable | Default Value | Description |
| :--- | :---: | :--- |
| `RUN_BENCHMARK` | `false` | Gatekeeper flag. Must be set to `true` to activate heavy benchmarks. |
| `BENCH_TRIPS` | `10000` | Number of trips evaluated per Level A scenario. |
| `BENCH_PINGS` | `1500` | Average raw GPS pings generated per trip (~1 ping per 2-5 seconds). |
| `BENCH_THREADS` | Available Cores | Worker thread count for concurrent scenarios (A3, A4). |
| `BENCH_PLACES` | `150` | Number of destination coordinates in the synthetic urban world model. |
| `BENCH_REPEATS` | `3` | Iteration repeat count for stability verification. |
| `BENCH_E2E_TRIPS` | `200` | Total complete trips processed in Level B REST benchmark. |
| `BENCH_E2E_THREADS` | `4` | Concurrent HTTP client threads in Level B benchmark. |
| `BENCH_SIM_LATENCY_MS` | `20` | Simulated upstream routing provider latency in Scenario A8. |
| `BENCH_SIM_TRIPS` | `200` | Trip count evaluated for simulated upstream latency in Scenario A8. |
| `BENCH_SEED` | `42` | Pseudorandom seed ensuring 100% reproducible synthetic generation. |

---

## 3. How to Run

All commands must be executed using Windows PowerShell from the repository root.

### A. Fast Unit Test Suite (Benchmarks Skipped)
By default, the heavy benchmark suites are disabled, allowing the unit and regression guard suite to complete in seconds:
```powershell
.\mvnw.cmd test "-Dgroups=unit"
```

### B. Run Level A Engine Benchmark (Plain-Java Core)
Enables `RUN_BENCHMARK` and runs scenarios A1 through A8:
```powershell
$env:RUN_BENCHMARK="true"; .\mvnw.cmd test "-Dtest=EngineBenchmark"
```

### C. Run Level A with Memory Constraints (-Xmx512m)
Demonstrates that streaming 10,000 trips operates comfortably within a constrained 512 MB heap:
```powershell
$env:RUN_BENCHMARK="true"; .\mvnw.cmd test "-Dtest=EngineBenchmark" "-DargLine=-Xmx512m"
```

### D. Run Level B End-to-End System Benchmark (REST + PostgreSQL)
Requires Docker Desktop running for Testcontainers PostgreSQL:
```powershell
$env:RUN_BENCHMARK="true"; .\mvnw.cmd test "-Dtest=EndToEndBenchmark"
```

---

## 4. Benchmark Scenarios Summary

### Level A Scenarios
- **A1: Single-Threaded Uncached Baseline:** 1 worker thread, direct routing calculation via `FakeRoutingProvider`.
- **A2: Single-Threaded Cached:** 1 worker thread with `CachingRoutingProvider` (snapped grid-cell caching).
- **A3: Multi-Threaded Cached:** Concurrent worker threads executing with shared thread-safe Caffeine cache. Asserts `checksum(A3) == checksum(A2)`.
- **A4: Multi-Threaded Uncached:** Concurrent worker threads without cache. Asserts `checksum(A4) == checksum(A1)`.
- **A5: Memory-Constrained Execution:** Evaluates peak heap memory usage against JVM heap limits.
- **A6: Scaling Curve:** Evaluates throughput and latency across ping density steps (500, 1,500, 3,000, 6,000 pings/trip).
- **A7: Upstream Failure & Recomputation:** Simulates upstream provider outage at $T/2$, trips circuit breaker OPEN, records degraded trips, recovers upstream, and asserts that recomputed trips restore baseline checksum.
- **A8: Simulated Upstream Latency:** Compares uncached vs cached execution with 20 ms simulated routing latency.

### Level B Scenarios
- Full trip lifecycle over HTTP:
  1. `POST /api/v1/trips`
  2. `POST /api/v1/trips/{id}/pings`
  3. `POST /api/v1/trips/{id}/milestones`
  4. `POST /api/v1/trips/{id}/calculate`
  5. `GET /api/v1/trips/{id}`
  6. `GET /api/v1/trips/{id}/segments`
- Measures p50, p95, p99, and max latency per endpoint.
- Queries PostgreSQL physical table sizes and row counts.

---

## 5. Artifacts & Results

Generated results are automatically saved to:
- `docs/benchmarks/results-<yyyyMMdd-HHmmss>.md`: Immutable timestamped run result.
- `docs/benchmarks/results-latest.md`: Symlink / pointer to the most recent run.

