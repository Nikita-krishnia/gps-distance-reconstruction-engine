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

## Concurrency and Consistency

Distance reconstruction guarantees strict consistency and ledger auditability using a three-layer concurrency control architecture:

1. **Row Lock on Trip (`PESSIMISTIC_WRITE`):**
   - Serializes decisions per trip within a short Phase 3 write transaction (~1-2 milliseconds).
   - CPU and external routing provider calls execute entirely **outside** database transactions (Phase 2), preventing connection pool starvation and lock contention.
   - Under lock, the service re-reads existing calculations (`findExistingCalculation` by `inputHash` and `ruleVersion`). If another thread completed identical calculation during Phase 2, the newly routed result is discarded and the existing record is reused (`reused: true`), returning `200 OK`.
2. **Database Unique Constraints:**
   - `uk_calc_trip_hash_ver_status (trip_id, input_hash, rule_version, status)` on `distance_calculation`: acts as the absolute data-layer safety net against concurrent duplicate inserts.
   - `uk_adjustment_trip_new_calc (trip_id, new_calculation_id)` on `billing_adjustment`: guarantees a new calculation can generate at most one adjustment line in the ledger.
3. **Optimistic Locking (`@Version Long version` on `Trip`):**
   - Catches any administrative or asynchronous update path that bypassed the row lock, immediately rejecting concurrent modifications with `409 CONCURRENT_MODIFICATION`.

### Why Distributed Transactions Are Not Needed
- **Per-Trip Linearizability:** All state changes for a single trip are strictly serialized by PostgreSQL row locks.
- **Append-Only Immutable Ledger:** Closed calculations and invoices never mutate. New calculations append new rows to `distance_calculation` and ledger entries to `billing_adjustment`.
- **Eventual Consistency:** The automatic recompute background worker (`DegradedTripRecomputeJob`) detects `DEGRADED` trips and recalculates them automatically once the upstream provider and circuit breaker recover, achieving eventual consistency within the configured polling window.

## Rounding & Precision Trade-offs

- **Segment Distances:** Each segment distance calculation (aerial straight-line, parked zero, routed road distance, or 1.4 aerial fallback) is rounded to whole meters (`Math.round(distanceMeters)`).
- **Deterministic Sum:** The trip total distance is the exact mathematical sum of these whole-meter `long` values. This guarantees 100% deterministic, reproducible totals without floating-point accumulation drift or rounding discrepancies across platforms.
- **Micro-distance Trade-off:** Segments shorter than 0.5 meters (e.g. slight jitter while parked) round down to 0 meters. This minor micro-distance undercounting is an intentional trade-off in favor of audit-proof integer precision.

## REST API Reference

> [!IMPORTANT]
> **Authentication Notice:** The `X-Tenant-Id` header is a multi-tenancy discriminator and **NOT** a real authentication mechanism. Secure JWT-based authentication and role-based access control will be implemented in Batch 10.

Every endpoint requires the header `X-Tenant-Id: <tenant-id>` (1 to 64 alphanumeric characters, dashes, or underscores). If omitted or invalid, the API returns `400 Bad Request`.

### Endpoints Overview

| Method | Endpoint | Status | Description & Validation |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/trips` | `201 Created` | Creates a new trip. Body requires `officePlannedLatitude` (-90..90) and `officePlannedLongitude` (-180..180). Returns `id`, `status: NEW`, `billingStatus: OPEN`, `createdAt`. Sets `Location: /api/v1/trips/{id}`. |
| `POST` | `/api/v1/trips/{id}/pings` | `201 Created` | Uploads 1 to 10,000 raw GPS pings (`latitude`, `longitude`, `recordedAt`, optional `accuracyMeters` >= 0). Stores raw data without cleaning. Returns `{ "accepted": n }`. |
| `POST` | `/api/v1/trips/{id}/milestones` | `201 Created` | Uploads 1 to 500 milestones (`TRIP_START`, `PICKUP`, `DROP`, `NO_SHOW`, `TRIP_END`). `employeeRef` required for pickups/drops/no-shows (letters, numbers, `_`, `-` only). Coordinates must be paired. Returns `{ "accepted": n }`. |
| `POST` | `/api/v1/trips/{id}/calculations` | `201 Created` / `200 OK` | Runs idempotent 3-phase calculation. Returns `201 Created` for fresh calculations; returns `200 OK` with `reused: true` if timeline fingerprint matches existing calculation. If trip is `CLOSED`, recalculation generates a ledgered `BillingAdjustment`. |
| `POST` | `/api/v1/trips/{id}/close-invoice` | `200 OK` | Closes billing for trip. Locks the latest `CALCULATED` distance as `billedCalculation`. Subsequent calls are idempotent (`200 OK`). Returns 409 if latest calculation is `DEGRADED` or if data changed since last calculation. |
| `GET` | `/api/v1/trips/{id}` | `200 OK` | Retrieves trip overview, total ping count, milestone count, latest calculation summary, `billedMeters`, `adjustmentsTotalMeters`, and `payableMeters`. |
| `GET` | `/api/v1/trips/{id}/calculations` | `200 OK` | Retrieves chronological history of calculations for the trip, newest first. |
| `GET` | `/api/v1/trips/{id}/calculations/{calcId}/segments` | `200 OK` | Retrieves ordered list of reconstructed segments with meters, rules, and privacy-safe explanation reasons. |
| `GET` | `/api/v1/trips/{id}/billing-adjustments` | `200 OK` | Retrieves billing adjustments history for the trip, newest first. |

### Closed-Invoice & Billing Adjustment Walkthrough

Demonstrating idempotent calculation, invoice closure, and signed delta ledger adjustment:

```powershell
# 1. Create a trip
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips `
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-alpha" `
  -d '{"officePlannedLatitude": 12.9279, "officePlannedLongitude": 77.6271}'

# 2. Upload raw GPS pings and milestones
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/pings `
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-alpha" `
  -d '{"pings": [{"latitude": 12.9716, "longitude": 77.5946, "recordedAt": "2026-10-30T07:00:00Z", "accuracyMeters": 10.0}]}'

curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/milestones `
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-alpha" `
  -d '{"milestones": [{"type": "TRIP_START", "actualTime": "2026-10-30T07:00:00Z"}, {"type": "TRIP_END", "actualLatitude": 12.9280, "actualLongitude": 77.6270, "actualTime": "2026-10-30T07:30:00Z"}]}'

# 3. Calculate distance (First run -> 201 Created, totalMeters=7762)
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/calculations `
  -H "X-Tenant-Id: tenant-alpha"

# 4. Calculate again (Idempotent reuse -> 200 OK, reused=true, totalMeters=7762)
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/calculations `
  -H "X-Tenant-Id: tenant-alpha"

# 5. Close invoice (200 OK, billedMeters=7762, billedKm=7.762)
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/close-invoice `
  -H "X-Tenant-Id: tenant-alpha"

# 6. Late GPS ping arrives after invoice closed
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/pings `
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-alpha" `
  -d '{"pings": [{"latitude": 12.9500, "longitude": 77.6100, "recordedAt": "2026-10-30T07:15:00Z", "accuracyMeters": 8.0}]}'

# 7. Recalculate closed trip (201 Created, totalMeters=7765, adjustment deltaMeters=+3)
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/calculations `
  -H "X-Tenant-Id: tenant-alpha"

# 8. Inspect trip details (billedMeters=7762, adjustmentsTotalMeters=3, payableMeters=7765)
curl.exe -s -i http://localhost:8081/api/v1/trips/1 `
  -H "X-Tenant-Id: tenant-alpha"

# 9. Inspect billing adjustment ledger history
curl.exe -s -i http://localhost:8081/api/v1/trips/1/billing-adjustments `
  -H "X-Tenant-Id: tenant-alpha"
```

## Known Limitations & Trade-offs

- **Segment Pagination:** Segment listings currently return all segments for a calculation without pagination. For very long routes (e.g. 1000+ segments), pagination will be considered.
- **Single-Instance Recompute Worker:** `DegradedTripRecomputeJob` is optimized for single-instance execution. While fully concurrent-safe across multiple nodes (enforced by PostgreSQL row locks and unique constraints), distributed leader election or `FOR UPDATE SKIP LOCKED` is omitted for simplicity.
- **Identity Key Generation:** JPA entities use `GenerationType.IDENTITY`, which disables Hibernate JDBC batch inserts on `saveAll()`. This is an accepted simplicity trade-off for prototyping and will be revisited if write throughput becomes a bottleneck in Batch 12 benchmarks.

## Status: Batch 9 (Idempotent Recompute, Invoices & Concurrency)

### Implemented vs Planned

| Feature / Requirement | Planned | Implemented | Status |
| :--- | :--- | :--- | :--- |
| **REST Controllers & DTOs** | Controllers in `api`, DTO records, no entities exposed | `TripController` and clean Java record DTOs with Bean Validation | Matched |
| **Multi-Tenancy Isolation** | Enforce `X-Tenant-Id` header, tenant-scoped lookups, cross-tenant 404 | Tenant validation, `findByIdAndTenantId`, strict cross-tenant 404 | Matched |
| **3-Phase Calculation Service** | Read Tx -> CPU/Routing (No Tx) -> Write Tx; no holding locks across HTTP | `CalculationService` + `TripDataPersistenceService` using separate beans | Matched |
| **Timeline Canonical Fingerprint** | Canonical SHA-256 binary hash over post-cleaning timeline, config & rule version | `TimelineFingerprint` (Double.doubleToLongBits, epoch sec+nano, lengths) | Matched |
| **Three-Layer Concurrency Control** | PESSIMISTIC_WRITE row lock, unique DB constraints, optimistic `@Version` | Handled via `TripRepository`, PostgreSQL unique constraints, `@Version` | Matched |
| **Idempotent Recompute** | Reuse existing calculation on identical data; 201 on new, 200 on reused | Phase 1 & Phase 3 re-check, `reused: true`, `reused: false` | Matched |
| **Invoice Closing & Audit Ledger** | `POST /close-invoice`, immutable billed baseline, signed delta adjustments | `closeInvoice` endpoint, `BillingAdjustment` ledger, signed deltas | Matched |
| **Automatic Recomputation Job** | Background worker recomputing `DEGRADED` trips when provider recovers | `DegradedTripRecomputeJob` with circuit breaker gate & thread-safe stats | Matched |
| **Rule Drift Golden Test** | Freeze 2026.1 rules and assert exact sequence, meters, reasons & total | `RuleDriftGuardGoldenTest` (exact meters & 100-run determinism) | Matched |
| **Security & Authentication** | JWT-based auth replacing temporary X-Tenant-Id header | **PLANNED (Batch 10)** | Planned |
| **Metrics & Observability** | Micrometer, Actuator, Prometheus metrics binding | **PLANNED (Batch 13)** | Planned |
