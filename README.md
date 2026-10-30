# GPS Distance Reconstruction Engine

A backend service built with Spring Boot 3.5.x and Java 21 to reconstruct trusted, explainable, and audit-proof billable distances for cab vendor trips from noisy GPS streams, raw employee pickup/drop milestones, and office anchor constraints.

## Database Setup

The development PostgreSQL database runs in a Docker container.

### Start Database
```powershell
docker compose up -d
```

- **Host Port**: `5434` (mapped to `5432` internally to avoid collisions with local PostgreSQL instances).
- **Environment**: Configured via `.env` (copy from `.env.example`).
- **Healthcheck**: Validates readiness before taking connections.

To inspect database container status:
```powershell
docker compose ps
```

> [!NOTE]
> `spring.jpa.hibernate.ddl-auto=update` is currently configured in `application.properties` as a temporary convenience during early prototyping. This trade-off will be replaced with production-grade, versioned migrations (Flyway) in a later batch.

## How to Run the Application

To run the application locally on port 8081:

```powershell
.\mvnw.cmd spring-boot:run
```

Verify the service status:

```powershell
curl.exe http://localhost:8081/api/v1/hello
```

Expected output:
```json
{"status":"UP","service":"gps-distance-engine"}
```

## How to Test

### 1. Engine & Routing Tests (Fast, Plain Java, No Docker Required)
The core mathematical, GPS cleaning, anchor merging, distance calculation engine, routing providers, caching, and circuit breaker run without Docker or Spring context:

```powershell
# Engine tests (44 tests)
.\mvnw.cmd test -Dtest="com.example.gpsengine.engine.*Test"

# Routing tests (43 tests, 1 optional live test skipped by default)
.\mvnw.cmd test -Dtest="com.example.gpsengine.routing.*Test"
```

### 2. Full Test Suite (Requires Docker Desktop)
Runs all 93 active tests (engine tests, routing tests, web slice test, and repository integration tests against a temporary Testcontainers PostgreSQL container):

```powershell
.\mvnw.cmd test
```

## Routing Providers, Caching & Circuit Breaker

### Architectural Chain
Routing requests follow a decorated pipeline:
```text
DistanceEngine 
    ──> CachingRoutingProvider (Caffeine) 
            ──> CircuitBreakerRoutingProvider (Hand-written FSM) 
                    ──> Raw Routing Provider (Fake or OSRM)
```
- **Deliberate Availability Choice:** Cache lookups sit outside the circuit breaker. When the circuit breaker trips `OPEN` due to an upstream outage, cached routes continue to be served seamlessly (`source = CACHE`), preserving high availability for frequent commute corridors.

### Caching, Circuit Breaker and Degraded Mode

| Dimension | Policy / Choice | Trade-off Description |
| :--- | :--- | :--- |
| **Consistency vs Availability** | High Availability | Cache hits bypass the breaker during outages. If upstream road conditions or geometries change, cached answers will remain in use until TTL expires. |
| **Latency vs Freshness** | Low Latency (~sub-millisecond) | Lookups are in-memory (Caffeine). Entries live up to `routing.cache.ttl-hours` (default 24h) before eviction/reload. |
| **Grid Snapping vs Accuracy** | Grid Snapping (~110m cells) | Coordinate endpoints snap to grid cells (`0.001` degrees $\approx 111\text{m}$). Each endpoint is queried using the **centre of the cell**, displacing endpoints by at most $\approx 78\text{m}$. This guarantees **100% determinism** across threads and arrival orders at the cost of slight endpoint displacement. |
| **Memory Bound** | Window TinyLFU Eviction | In-memory cache is capped at `routing.cache.max-entries` (default 100,000), bounding heap usage. |
| **Degraded Mode** | Straight-line $\times 1.4$ Fallback | When upstream fails or breaker is `OPEN`, segments fallback to aerial $\times 1.4$ and the trip is marked `TripStatus.DEGRADED`. |

> [!IMPORTANT]
> **Automatic Recomputation Status:** Automatic background recomputation of `DEGRADED` trips is **NOT implemented yet**. It is planned for **Batch 9** as a scheduled background recovery job once database persistence and service layers are complete.

## Rounding & Precision Trade-offs

- **Segment Distances:** Each segment distance calculation (aerial straight-line, parked zero, routed road distance, or 1.4 aerial fallback) is rounded to whole meters (`Math.round(distanceMeters)`).
- **Deterministic Sum:** The trip total distance is the exact mathematical sum of these whole-meter `long` values. This guarantees 100% deterministic, reproducible totals without floating-point accumulation drift or rounding discrepancies across platforms.
- **Micro-distance Trade-off:** Segments shorter than 0.5 meters (e.g. slight jitter while parked) round down to 0 meters. This minor micro-distance undercounting is an intentional trade-off in favor of audit-proof integer precision.

## Status: Batch 7 (Routing Cache, Counters & Circuit Breaker)

### Implemented vs Planned

| Feature / Requirement | Planned | Implemented | Status |
| :--- | :--- | :--- | :--- |
| **Caffeine In-Memory Cache** | Caffeine cache with maximumSize, expireAfterWrite TTL, grid snapping, centre-to-centre routing | `CachingRoutingProvider` with directional snapped grid keys, centre routing, and same-cell bypass | Matched |
| **Deterministic Caching** | Snap to cell centres so cached values depend only on the key, not arrival order | Centre coordinate conversion for from/to endpoints | Matched |
| **Same-Cell Bypass** | When start/end share same cell, bypass cache, route exact coords, do not cache | Same-cell detection with direct pass-through and `cacheBypasses` counter | Matched |
| **Circuit Breaker** | States CLOSED, OPEN, HALF_OPEN; threshold failures; open duration; single trial | Plain-Java `CircuitBreaker` with synchronized state machine and lock-free execution | Matched |
| **Concurrency & Non-Blocking** | Never hold lock while provider call runs | Lock released during upstream I/O; trial in-flight guard prevents multiple probes | Matched |
| **Routing Telemetry** | Thread-safe counters for hits, misses, bypasses, provider calls, failures, breaker rejections | `RoutingStats` with `LongAdder` counters and immutable `Snapshot` | Matched |
| **Per-Trip Stats in Engine** | `DistanceResult` tracks `providerCalls`, `cacheHits`, and `cacheMisses` | `DistanceEngine` populates per-trip metrics from `RouteResult.source()` | Matched |
| **Runtime Failure Control** | `FakeRoutingProvider` toggleable failure for recovery verification | `setFailing(boolean)` added to `FakeRoutingProvider` | Matched |
| **Decorated Wiring** | Chain: primary `CachingRoutingProvider` -> `CircuitBreakerRoutingProvider` -> raw provider | `RoutingConfiguration` wires chain, stats, breaker, and clock beans | Matched |
| **Automatic Recomputation Job** | Scheduled background worker recomputing DEGRADED trips upon provider recovery | **PLANNED (Batch 9)** | Planned |
