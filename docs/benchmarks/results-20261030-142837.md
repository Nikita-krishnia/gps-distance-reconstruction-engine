# GPS Reconstruction Engine - Benchmark Report

**Run Timestamp (UTC):** `20261030-142837`  
**Execution Environment:** Clean Host Environment  

## 1. System & Hardware Specifications

| Component | Specification |
| :--- | :--- |
| **Operating System** | Windows 11 10.0 (amd64) |
| **Java Runtime** | OpenJDK Runtime Environment 21.0.12.1 (Eclipse Adoptium) |
| **JVM Maximum Heap (-Xmx)** | 512 MB |
| **Available Logical Processors** | 12 cores |
| **Benchmark Threads (BENCH_THREADS)** | 12 threads |
| **Power Source** | AC Line Connected (Unthrottled) |

## 2. Level A: Pure-Java Engine Benchmark Results

Benchmark dataset: **10000 trips**, each with **~1500 raw GPS pings** across a 20 km x 20 km world model (150 places, power-law popularity distribution).

| Scenario | Description | Wall (s) | Pipeline (s) | Throughput (trips/s) | Throughput (pings/s) | p50 (μs) | p95 (μs) | p99 (μs) | Max (μs) | Peak Heap (MB) | GC Count | GC Time (ms) | Cache Hit % |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **A1** | 1 thread, cache disabled (FakeRoutingProvider) | 28.38 | 27.12 | 352.4 | 502108 | 2579.6 | 3975.8 | 5094.7 | 11809.8 | 100 | 303 | 322 | 0.0% |
| **A2** | 1 thread, cache enabled (CachingRoutingProvider) | 27.66 | 26.67 | 361.6 | 515215 | 2533.7 | 3998.4 | 4922.2 | 13623.7 | 107 | 300 | 419 | 34.4% |
| **A3** | 12 threads, cache enabled (CachingRoutingProvider) | 6.50 | 75.11 | 1538.1 | 2307101 | 5728.5 | 17339.4 | 32957.6 | 82482.9 | 249 | 123 | 405 | 34.4% |
| **A4** | 12 threads, cache disabled (FakeRoutingProvider) | 6.09 | 70.00 | 1643.4 | 2465046 | 5695.8 | 14180.5 | 25325.4 | 69546.5 | 283 | 106 | 344 | 0.0% |
| **A5** | Memory-constrained execution (Peak Heap vs Max Heap) | 27.66 | 26.67 | 361.6 | 515215 | 2533.7 | 3998.4 | 4922.2 | 13623.7 | 107 | 300 | 419 | 34.4% |

### Determinism & Checksums

| Scenario | Total Distance (m) | SHA-256 Output Checksum | Invariant Match |
| :--- | :---: | :--- | :--- |
| **A1** | 520,456,982 | `12035b3ca096a49f81174e3826be848a0093a7c2143406493eae2a76a5b08b19` | Baseline |
| **A2** | 520,510,249 | `78719cc532df1c3f80286d3e3e40dafff8b36c0c65667d9bd3cf2ba190417275` | Baseline |
| **A3** | 520,510,249 | `78719cc532df1c3f80286d3e3e40dafff8b36c0c65667d9bd3cf2ba190417275` | Matches A2 (100% Deterministic Concurrency) |
| **A4** | 520,456,982 | `12035b3ca096a49f81174e3826be848a0093a7c2143406493eae2a76a5b08b19` | Matches A1 (100% Deterministic Concurrency) |

**Accuracy Cost of Caching (A1 Uncached vs A2 Snapped Cell Cache):**  
- Uncached Total Distance: 520,456,982 metres
- Cached Total Distance: 520,510,249 metres
- Discrepancy from Cell Centre Snapping: **0.0102%** (Bounded within 0.1% rule)

## 3. Scenario A6: Scaling Curve (Ping Density)

Evaluates processing latency per trip as ping density increases (500 trips, 1 thread):

| Target Pings/Trip | Actual Pings Evaluated | Wall Time (s) | Throughput (pings/s) | p50 Latency (μs) | Latency per Ping (μs) |
| :---: | :---: | :---: | :---: | :---: | :---: |
| 500 | 237,500 | 0.39 | 608,329 | 733.8 | 1.576 |
| 1500 | 712,500 | 1.26 | 564,608 | 2417.7 | 1.711 |
| 3000 | 1,425,000 | 2.66 | 535,605 | 4933.1 | 1.809 |
| 6000 | 2,850,000 | 5.28 | 539,598 | 9799.1 | 1.795 |

**Complexity Analysis:** Cleaning and timeline merging operate in $O(n \log n)$ time due to timestamp sorting. Latency per ping remains flat and slightly logarithmic across an order of magnitude increase in ping density.

## 4. Scenario A7: Failure Injection & Recompute Recovery

- **Total Trips Evaluated:** 10000  
- **Failure Injected at Trip:** 5000 (Circuit breaker tripped OPEN)  
- **Degraded Trips Recorded:** 4281 trips  
- **Fallback 1.4x Aerial Segments Produced:** 10607 segments  
- **Heal & Recompute Recovery:** Circuit breaker reset, provider restored, degraded trips re-evaluated  
- **Recovered Checksum Matches Clean Run Baseline:** **YES (Verified)**  
  - Initial Degraded Checksum: `9e2a180e5f122de48f7c9e1ff2efe60681a5c86d5cee7c2ec69db8893d8bb437`  
  - Recovered Output Checksum: `78719cc532df1c3f80286d3e3e40dafff8b36c0c65667d9bd3cf2ba190417275`  
  - Clean Baseline Checksum: `78719cc532df1c3f80286d3e3e40dafff8b36c0c65667d9bd3cf2ba190417275`  

## 5. Scenario A8: Simulated Upstream Latency (20 ms)

Evaluates the caching speedup when upstream routing queries incur realistic network latency (20 ms):

- **Trips Processed:** 200 trips
- **Simulated Latency:** 20 ms per provider call
- **Uncached Wall-Clock Time:** 29.65 seconds
- **Cached Wall-Clock Time:** 29.03 seconds
- **Cache Hit Rate:** **2.22%** (21 hits / 0 lookups)
- **Effective Speedup:** **1.0x faster** with cache enabled


## 6. Level B: End-to-End REST & PostgreSQL System Benchmark

Evaluates the full Spring Boot 3 stack under real HTTP client concurrency (4 client threads, 200 complete trips, 50,000 GPS pings) backed by PostgreSQL 17 via Testcontainers.

- **Total Wall-Clock Time:** 56.90 seconds  
- **Completed Trips Throughput:** **3.5 trips/sec**  
- **Ping Ingestion Throughput:** **879 pings/sec**  

### Endpoint Latency Percentiles

| Endpoint / Operation | HTTP Method | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| POST /api/v1/trips (Create Trip) | POST | 10.29 ms | 20.66 ms | 28.00 ms | 38.56 ms |
| POST /api/v1/trips/{id}/pings (Batch Upload) | POST | 683.29 ms | 866.99 ms | 1020.88 ms | 1031.87 ms |
| POST /api/v1/trips/{id}/milestones (Batch Upload) | POST | 26.55 ms | 47.41 ms | 78.25 ms | 86.76 ms |
| POST /api/v1/trips/{id}/calculations (Reconstruct Distance) | POST | 323.56 ms | 606.58 ms | 800.41 ms | 800.67 ms |
| GET /api/v1/trips/{id} (Trip Summary) | GET | 19.66 ms | 39.04 ms | 55.49 ms | 68.31 ms |
| GET /api/v1/trips/{id}/calculations/{calcId}/segments (Audit Trail) | GET | 22.97 ms | 42.44 ms | 64.01 ms | 223.91 ms |

### PostgreSQL Table Storage & Row Counts

| Database Table | Row Count | Disk Size (Total Relation) |
| :--- | :---: | :---: |
| `trip` | 210 | 104 kB |
| `gps_ping` | 49,770 | 7672 kB |
| `trip_milestone` | 1,078 | 248 kB |
| `trip_segment` | 21,056 | 5472 kB |
| `distance_calculation` | 210 | 168 kB |
| `billing_adjustment` | 0 | 32 kB |
| `app_user` | 1 | 40 kB |

