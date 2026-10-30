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
| `POST` | `/api/v1/trips/{id}/calculations` | `201 Created` | Runs the 3-phase calculation pipeline. Calculates or recomputes distance and saves segments. Returns calculation summary and 3-decimal `totalKm`. |
| `GET` | `/api/v1/trips/{id}` | `200 OK` | Retrieves trip overview, total ping count, milestone count, and latest calculation summary. |
| `GET` | `/api/v1/trips/{id}/calculations` | `200 OK` | Retrieves chronological history of calculations for the trip, newest first. |
| `GET` | `/api/v1/trips/{id}/calculations/{calcId}/segments` | `200 OK` | Retrieves ordered list of reconstructed segments with meters, rules, and privacy-safe explanation reasons. |
| `GET` | `/api/v1/trips/{id}/billing-adjustments` | `200 OK` | Retrieves billing adjustments history for the trip, newest first. |

### PowerShell Walkthrough Using curl.exe

Run this walkthrough against a running service (`.\mvnw.cmd spring-boot:run` on port 8081):

```powershell
# 1. Create a trip
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips `
  -H "Content-Type: application/json" `
  -H "X-Tenant-Id: tenant-alpha" `
  -d "@docs/samples/trip.json"

# 2. Upload raw GPS pings (including duplicates and out-of-order)
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/pings `
  -H "Content-Type: application/json" `
  -H "X-Tenant-Id: tenant-alpha" `
  -d "@docs/samples/pings.json"

# 3. Upload milestones (start, employee drop, and trip end)
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/milestones `
  -H "Content-Type: application/json" `
  -H "X-Tenant-Id: tenant-alpha" `
  -d "@docs/samples/milestones.json"

# 4. Calculate distance
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/calculations `
  -H "X-Tenant-Id: tenant-alpha"

# 5. Get trip summary
curl.exe -s -i http://localhost:8081/api/v1/trips/1 `
  -H "X-Tenant-Id: tenant-alpha"

# 6. Get calculation segments
curl.exe -s -i http://localhost:8081/api/v1/trips/1/calculations/1/segments `
  -H "X-Tenant-Id: tenant-alpha"
```

## Known Limitations & Trade-offs

- **Segment Pagination:** Segment listings currently return all segments for a calculation without pagination. For very long routes (e.g. 1000+ segments), pagination will be considered.
- **Concurrent Calculate Calls:** Concurrent `POST /calculations` requests on the exact same trip will execute and create separate calculation snapshots. Concurrency locking and idempotency keys will be added in Batch 9.
- **Identity Key Generation:** JPA entities use `GenerationType.IDENTITY`, which disables Hibernate JDBC batch inserts on `saveAll()`. This is an accepted simplicity trade-off for prototyping and will be revisited if write throughput becomes a bottleneck in Batch 12 benchmarks.

## Status: Batch 8 (Service Layer, REST APIs & Validation)

### Implemented vs Planned

| Feature / Requirement | Planned | Implemented | Status |
| :--- | :--- | :--- | :--- |
| **REST Controllers & DTOs** | Controllers in `api`, DTO records, no entities exposed | `TripController` and clean Java record DTOs with Bean Validation | Matched |
| **Multi-Tenancy Isolation** | Enforce `X-Tenant-Id` header, tenant-scoped lookups, cross-tenant 404 | Tenant validation, `findByIdAndTenantId`, strict cross-tenant 404 | Matched |
| **3-Phase Calculation Service** | Read Tx -> CPU/Routing (No Tx) -> Write Tx; no holding locks across HTTP | `CalculationService` + `TripDataPersistenceService` using separate beans | Matched |
| **Standardized Error Handling** | Single envelope `{timestamp, status, code, message, path, correlationId}` | `@RestControllerAdvice` in `GlobalExceptionHandler` | Matched |
| **Privacy by Design** | No coordinates, employee refs, or rejected values in responses or logs | Field names and error reasons only; masked internal 500 errors | Matched |
| **Idempotent Recompute & Billing Adjustments** | Handling late pings on closed invoices via signed adjustments | **PLANNED (Batch 9)** | Planned |
| **Automatic Recomputation Job** | Scheduled background worker recomputing DEGRADED trips upon provider recovery | **PLANNED (Batch 9)** | Planned |
| **Security & Authentication** | JWT-based auth replacing temporary X-Tenant-Id header | **PLANNED (Batch 10)** | Planned |
| **Metrics & Observability** | Micrometer, Actuator, Prometheus metrics binding | **PLANNED (Batch 13)** | Planned |
