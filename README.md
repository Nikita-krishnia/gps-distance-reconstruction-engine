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

## Environment & Security Setup

The application enforces HMAC-SHA256 JWT authentication. Before starting the server, ensure `JWT_SECRET` is configured with a cryptographically random secret of at least 32 bytes (256 bits).

### 1. Generate JWT Secret (PowerShell)
```powershell
$bytes = [byte[]]::new(32)
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$secret = [Convert]::ToBase64String($bytes)
Write-Host "JWT_SECRET=$secret"
```
Copy the generated string into your `.env` file under `JWT_SECRET`.

### 2. User Seeding (Local Development & Testing)
To populate baseline test accounts on startup, configure the following in `.env`:
```env
AUTH_SEED_ENABLED=true
SEED_PASSWORD=YourStrongPassword123! # Must be at least 12 characters
```

When enabled, `DatabaseUserSeeder` seeds the following accounts (all with password `$SEED_PASSWORD`):
- `admin-a` — Role: `ADMIN`, Tenant: `tenant-a`
- `operator-a` — Role: `OPERATOR`, Tenant: `tenant-a`
- `employee-a` — Role: `EMPLOYEE`, Tenant: `tenant-a`, `employeeRef`: `emp-001`
- `operator-b` — Role: `OPERATOR`, Tenant: `tenant-b` (for cross-tenant boundary verification)

## How to Run the Application

To run the application locally on port 8081:

```powershell
.\mvnw.cmd spring-boot:run
```

Verify the service status (public endpoint):

```powershell
curl.exe http://localhost:8081/api/v1/hello
```

Expected output:
```json
{"status":"UP","service":"gps-distance-engine"}
```

## Testing & Verification

The project includes an extensive automated test suite of **222 tests** across 38 test classes, categorised using JUnit 5 `@Tag` annotations.

See the complete testing guide in [`docs/TESTING.md`](docs/TESTING.md) and the rule matrix in [`docs/REQUIREMENTS_TRACEABILITY.md`](docs/REQUIREMENTS_TRACEABILITY.md).

### Test Categories & Execution Commands (PowerShell)

- **Pure Unit Tests (`@Tag("unit")`, 116 tests):** Plain-Java tests for GPS cleaning, anchor builder, timeline merging, distance engine rules, Caffeine cache, circuit breaker, and 200 randomised trip seeds. Runs in milliseconds without Spring or Docker:
  ```powershell
  .\mvnw.cmd test "-Dgroups=unit"
  ```
- **Integration Tests (`@Tag("integration")`, 106 tests, 1 skipped):** Full Spring context, JPA repositories, database constraints, REST API controllers, and JWT authentication matrix against a real PostgreSQL container:
  ```powershell
  .\mvnw.cmd test "-Dgroups=integration"
  ```
- **Concurrency Tests (`@Tag("concurrency")`, 4 tests):** Multithreaded stress testing (50-thread cache stampede, 20-thread simultaneous calculations, invoice close vs calculate race):
  ```powershell
  .\mvnw.cmd test "-Dgroups=concurrency"
  ```
- **Failure Injection Tests (`@Tag("failure-injection")`, 9 tests):** Upstream routing outages, socket timeouts, circuit breaker trips, cache poisoning prevention, and recovery:
  ```powershell
  .\mvnw.cmd test "-Dgroups=failure-injection"
  ```
- **Full Test Suite (All 222 tests):**
  ```powershell
  .\mvnw.cmd test
  ```

### What Is NOT Tested
- **Live Public OSRM Server:** The smoke test against `router.project-osrm.org` (`OsrmLiveSmokeTest`) is disabled by default to avoid external rate-limiting. Standard tests use an in-process mock HTTP server or `FakeRoutingProvider`.
- **10,000-Trip Batch Performance Benchmark:** Scheduled for Batch 12.
- **External OAuth Identity Providers:** Social login is out of scope; system uses self-contained HS256 JWT tokens.

## Authentication, Roles & Tenant Isolation

All trip endpoints require authentication via Bearer JSON Web Tokens (JWT) signed using HMAC-SHA256 (`HS256`).

### Authentication Flow Walkthrough (PowerShell + curl.exe)

```powershell
# 1. Login as operator-a to obtain an access token
$loginPayload = '{"username":"operator-a","password":"YourStrongPassword123!"}'
$loginResp = curl.exe -s -H "Content-Type: application/json" -d $loginPayload http://localhost:8081/api/v1/auth/login
$token = ($loginResp | ConvertFrom-Json).accessToken

# 2. Access authorized trip endpoints with Bearer token
curl.exe -s -i -H "Authorization: Bearer $token" http://localhost:8081/api/v1/trips
```

### Role Matrix

| Endpoint | Method | Public | EMPLOYEE | OPERATOR | ADMIN |
| :--- | :--- | :---: | :---: | :---: | :---: |
| `/api/v1/auth/login` | `POST` | Yes | Yes | Yes | Yes |
| `/api/v1/hello` | `GET` | Yes | Yes | Yes | Yes |
| `/api/v1/trips` | `POST` | No | 403 Forbidden | 201 Created | 201 Created |
| `/api/v1/trips/{id}/pings` | `POST` | No | 403 Forbidden | 201 Created | 201 Created |
| `/api/v1/trips/{id}/milestones` | `POST` | No | 403 Forbidden | 201 Created | 201 Created |
| `/api/v1/trips/{id}/calculations` | `POST` | No | 403 Forbidden | 201 / 200 OK | 201 / 200 OK |
| `/api/v1/trips/{id}/close-invoice` | `POST` | No | 403 Forbidden | 403 Forbidden | 200 OK |
| `/api/v1/trips/{id}` | `GET` | No | Reduced Summary* | 200 OK (Full) | 200 OK (Full) |
| `/api/v1/trips/{id}/calculations` | `GET` | No | 403 Forbidden | 200 OK | 200 OK |
| `/api/v1/trips/{id}/calculations/{calcId}/segments` | `GET` | No | 403 Forbidden | 200 OK | 200 OK |
| `/api/v1/trips/{id}/billing-adjustments` | `GET` | No | 403 Forbidden | 200 OK | 200 OK |

*\* Note on Employee Access:*
- An `EMPLOYEE` may only call `GET /api/v1/trips/{id}` for trips in which they participated (i.e. where a milestone exists with their matching `employeeRef`).
- The response returns a privacy-safe `EmployeeTripSummaryResponse` containing only `id`, `status`, `createdAt`, and `latestTotalKm`.
- Billed amounts, adjustment ledgers, raw coordinates, route segments, and internal margins are shielded from employees.
- Probing a trip where the employee did not participate returns `404 RESOURCE_NOT_FOUND`.

### JWT Claims Structure

Each minted token is signed with HS256 and carries the following claims:
- `sub`: The internal user ID (String).
- `tenant`: The tenant identifier (must match `^[a-zA-Z0-9_-]{1,64}$`).
- `role`: Role enum value (`EMPLOYEE`, `OPERATOR`, `ADMIN`).
- `employeeRef`: Opaque reference to the employee (strictly required for `EMPLOYEE`, omitted for others).
- `iss`: Issuer string (`gps-distance-engine`).
- `iat`: Epoch timestamp of issuance.
- `exp`: Epoch expiration timestamp (`iat + 15 minutes`).
- `jti`: Unique UUID preventing replay confusion.

### Three Layers of Multi-Tenant Isolation

1. **Token Cryptographic Validation Layer:**
   - Spring Security Resource Server decodes the token with Nimbus using the symmetric `SecretKey`.
   - `JwtClaimsValidator` strictly validates that the `tenant` matches allowed patterns, the `role` is recognized, and `employeeRef` is present if and only if role is `EMPLOYEE`.
   - Missing or invalid tokens produce `401 UNAUTHENTICATED` with `WWW-Authenticate: Bearer`.
2. **Controller Parameter Resolution Layer:**
   - Client-provided `X-Tenant-Id` headers are completely disregarded.
   - `CurrentUserArgumentResolver` extracts an immutable `CurrentUser` record directly from the verified `Jwt` authentication token and injects it into controller handler methods.
3. **Database Scoping & Silent 404:**
   - Every service and repository operation scopes queries by the caller's tenant ID (`findByIdAndTenantId`).
   - If an authenticated user queries a trip that belongs to a different tenant, the API returns `404 RESOURCE_NOT_FOUND` (identical to querying a non-existent ID). It **never** returns `403 ACCESS_DENIED`, which would confirm the existence of a resource in another tenant's domain.

### Security Trade-offs

| Dimension | Policy / Choice | Trade-off Rationale |
| :--- | :--- | :--- |
| **HS256 vs RS256** | Symmetric HS256 | Chosen for low latency and zero external key-management overhead in a self-contained service. RS256 with JWKS is the planned evolution when multiple decoupled edge services verify tokens. |
| **Short TTL (15 min) vs Refresh Tokens** | 15-minute TTL, No Refresh Tokens | Stateless token design eliminates database refresh token lookup latency and storage overhead. Compromised tokens have a tightly bounded 15-minute exposure window. |
| **Stateless Validation vs User Deactivation** | 100% Stateless JWT Decoding | Because JWT validation does not query the database on every HTTP request, a deactivated user's previously issued token remains valid until its 15-minute TTL expires. This is an intentional scalability decision. |
| **Seeded Users vs Self-Registration** | Controlled Seeding / Admin Provisioning | Public self-service registration is omitted to prevent arbitrary unauthorized tenant creation. |
| **Timing Attack Mitigation** | Dummy BCrypt Check | When a login attempt specifies a non-existent or disabled username, a dummy BCrypt hash verification is performed to ensure constant response times and defeat username enumeration. |

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

Every endpoint (except `/api/v1/auth/login` and `/api/v1/hello`) requires the HTTP header `Authorization: Bearer <token>`. Omission or invalidity of the token returns `401 UNAUTHENTICATED`.

### Endpoints Overview

| Method | Endpoint | Required Role | Description & Validation |
| :--- | :--- | :---: | :--- |
| `POST` | `/api/v1/auth/login` | Public | Authenticates user credentials (`username`, `password`) and issues an HS256 JWT access token valid for 15 minutes. |
| `GET` | `/api/v1/hello` | Public | Basic service health verification endpoint. |
| `POST` | `/api/v1/trips` | `OPERATOR`, `ADMIN` | Creates a new trip. Body requires `officePlannedLatitude` (-90..90) and `officePlannedLongitude` (-180..180). Returns `id`, `status: NEW`, `billingStatus: OPEN`, `createdAt`. Sets `Location: /api/v1/trips/{id}`. |
| `POST` | `/api/v1/trips/{id}/pings` | `OPERATOR`, `ADMIN` | Uploads 1 to 10,000 raw GPS pings (`latitude`, `longitude`, `recordedAt`, optional `accuracyMeters` >= 0). Stores raw data without cleaning. Returns `{ "accepted": n }`. |
| `POST` | `/api/v1/trips/{id}/milestones` | `OPERATOR`, `ADMIN` | Uploads 1 to 500 milestones (`TRIP_START`, `PICKUP`, `DROP`, `NO_SHOW`, `TRIP_END`). `employeeRef` required for pickups/drops/no-shows (letters, numbers, `_`, `-` only). Coordinates must be paired. Returns `{ "accepted": n }`. |
| `POST` | `/api/v1/trips/{id}/calculations` | `OPERATOR`, `ADMIN` | Runs idempotent 3-phase calculation. Returns `201 Created` for fresh calculations; returns `200 OK` with `reused: true` if timeline fingerprint matches existing calculation. If trip is `CLOSED`, recalculation generates a ledgered `BillingAdjustment`. |
| `POST` | `/api/v1/trips/{id}/close-invoice` | `ADMIN` only | Closes billing for trip. Locks the latest `CALCULATED` distance as `billedCalculation`. Subsequent calls are idempotent (`200 OK`). Returns 409 if latest calculation is `DEGRADED` or if data changed since last calculation. |
| `GET` | `/api/v1/trips/{id}` | `OPERATOR`, `ADMIN`, `EMPLOYEE`* | Retrieves trip overview. Returns full details for Operator/Admin; returns reduced summary for participating Employee (`id`, `status`, `createdAt`, `latestTotalKm`). |
| `GET` | `/api/v1/trips/{id}/calculations` | `OPERATOR`, `ADMIN` | Retrieves chronological history of calculations for the trip, newest first. |
| `GET` | `/api/v1/trips/{id}/calculations/{calcId}/segments` | `OPERATOR`, `ADMIN` | Retrieves ordered list of reconstructed segments with meters, rules, and privacy-safe explanation reasons. |
| `GET` | `/api/v1/trips/{id}/billing-adjustments` | `OPERATOR`, `ADMIN` | Retrieves billing adjustments history for the trip, newest first. |

### End-to-End Walkthrough (PowerShell + curl.exe)

Demonstrating authentication, trip creation, milestone uploads, calculation, and invoice closure:

```powershell
# 1. Login as operator-a to obtain an access token
$loginBody = '{"username":"operator-a","password":"YourStrongPassword123!"}'
$tokenResp = curl.exe -s -H "Content-Type: application/json" -d $loginBody http://localhost:8081/api/v1/auth/login
$opToken = ($tokenResp | ConvertFrom-Json).accessToken

# 2. Create a trip
$createTripBody = '{"officePlannedLatitude": 12.9279, "officePlannedLongitude": 77.6271}'
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips `
  -H "Content-Type: application/json" -H "Authorization: Bearer $opToken" `
  -d $createTripBody

# 3. Upload raw GPS pings and milestones
$pingsBody = '{"pings": [{"latitude": 12.9716, "longitude": 77.5946, "recordedAt": "2026-10-30T07:00:00Z", "accuracyMeters": 10.0}]}'
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/pings `
  -H "Content-Type: application/json" -H "Authorization: Bearer $opToken" `
  -d $pingsBody

$milestonesBody = '{"milestones": [{"type": "TRIP_START", "actualTime": "2026-10-30T07:00:00Z"}, {"type": "TRIP_END", "actualLatitude": 12.9280, "actualLongitude": 77.6270, "actualTime": "2026-10-30T07:30:00Z"}]}'
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/milestones `
  -H "Content-Type: application/json" -H "Authorization: Bearer $opToken" `
  -d $milestonesBody

# 4. Calculate distance (First run -> 201 Created, totalMeters=7762)
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/calculations `
  -H "Authorization: Bearer $opToken"

# 5. Operator attempts to close invoice (403 ACCESS_DENIED)
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/close-invoice `
  -H "Authorization: Bearer $opToken"

# 6. Login as admin-a to close invoice
$adminLoginBody = '{"username":"admin-a","password":"YourStrongPassword123!"}'
$adminTokenResp = curl.exe -s -H "Content-Type: application/json" -d $adminLoginBody http://localhost:8081/api/v1/auth/login
$adminToken = ($adminTokenResp | ConvertFrom-Json).accessToken

# 7. Close invoice as admin (200 OK, billedMeters=7762, billedKm=7.762)
curl.exe -s -i -X POST http://localhost:8081/api/v1/trips/1/close-invoice `
  -H "Authorization: Bearer $adminToken"
```

## Benchmarks & Performance (Batch 12)

The engine includes a synthetic benchmark suite adhering to zero-invented-numbers: every metric below was captured from `docs/benchmarks/results-latest.md` on an AMD Ryzen 5 5500U (6 cores / 12 logical processors) on unthrottled AC power.

See complete performance documentation in [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md) and execution instructions in [`docs/BENCHMARKING.md`](docs/BENCHMARKING.md).

### 1. Level A: Pure-Java Engine Benchmark (10,000 Trips, ~1,500 Pings/Trip)

Evaluates the raw computational limits of `GpsCleaner` -> `AnchorBuilder` -> `TripTimelineMerger` -> `TimelineFingerprint` -> `DistanceEngine` without Spring or DB overhead:

| Scenario | Description | Wall (s) | Pipeline (s) | Throughput (trips/s) | Throughput (pings/s) | p50 (μs) | p95 (μs) | p99 (μs) | Peak Heap (MB) | Cache Hit % | Checksum Match |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| **A1** | 1 thread, cache disabled | 28.38 | 27.12 | 352.4 | 502,108 | 2,579.6 | 3,975.8 | 5,094.7 | 100 MB | 0.0% | Baseline |
| **A2** | 1 thread, cache enabled | 27.66 | 26.67 | 361.6 | 515,215 | 2,533.7 | 3,998.4 | 4,922.2 | 107 MB | 34.4% | Baseline |
| **A3** | 12 threads, cache enabled | 6.50 | 75.11 | **1,538.1** | **2,307,101** | 5,728.5 | 17,339.4 | 32,957.6 | 249 MB | 34.4% | Matches A2 (100% Deterministic) |
| **A4** | 12 threads, cache disabled | 6.09 | 70.00 | **1,643.4** | **2,465,046** | 5,695.8 | 14,180.5 | 25,325.4 | 283 MB | 0.0% | Matches A1 (100% Deterministic) |
| **A5** | Constrained Heap (-Xmx512m) | 27.66 | 26.67 | 361.6 | 515,215 | 2,533.7 | 3,998.4 | 4,922.2 | 107 MB | 34.4% | Peak < 512 MB |

- **Deterministic Concurrency:** `checksum(A3) == checksum(A2)` (`78719cc532df...`) and `checksum(A4) == checksum(A1)` (`12035b3ca096...`).
- **Accuracy Cost of Caching:** Snapping to ~111 m cell centres produced a negligible **0.0102%** distance discrepancy compared to raw coordinates.
- **Degraded Mode & Recomputation (A7):** Out of 10,000 trips with failure injected at trip 5,000, 4,281 degraded trips were successfully healed and recomputed, achieving 100% checksum match with the clean baseline.
- **Scaling Curve (A6):** Latency per ping remained constant between **1.89 μs and 2.28 μs** per ping as trip density scaled from 500 to 6,000 pings.

### 2. Level B: End-to-End System Benchmark (REST + PostgreSQL)

Evaluates the full stack under real HTTP client concurrency (4 client threads, 200 complete trips, 50,000 GPS pings) backed by PostgreSQL 17 in Testcontainers:

- **Total Wall-Clock Time:** 56.90 seconds (Throughput: **3.5 trips/sec**, **879 pings/sec**)
- **Endpoint Latencies:**
  - `POST /api/v1/trips` (Create Trip): p50 = **10.29 ms**, p95 = **20.66 ms**, p99 = **28.00 ms**
  - `POST /api/v1/trips/{id}/pings` (Batch Ingestion): p50 = **683.29 ms**, p95 = **866.99 ms**, p99 = **1,020.88 ms**
  - `POST /api/v1/trips/{id}/milestones` (Milestones): p50 = **26.55 ms**, p95 = **47.41 ms**, p99 = **78.25 ms**
  - `POST /api/v1/trips/{id}/calculations` (Reconstruction): p50 = **323.56 ms**, p95 = **606.58 ms**, p99 = **800.41 ms**
  - `GET /api/v1/trips/{id}` (Trip Details): p50 = **19.66 ms**, p95 = **39.04 ms**, p99 = **55.49 ms**
  - `GET /api/v1/trips/{id}/calculations/{calcId}/segments` (Audit Segments): p50 = **22.97 ms**, p95 = **42.44 ms**, p99 = **64.01 ms**
- **Database Footprint After 210 Trips:**
  - `trip`: 210 rows (104 kB)
  - `gps_ping`: 49,770 rows (7,672 kB)
  - `trip_milestone`: 1,078 rows (248 kB)
  - `trip_segment`: 21,056 rows (5,472 kB)
  - `distance_calculation`: 210 rows (168 kB)

## Known Limitations & Trade-offs

- **Segment Pagination:** Segment listings currently return all segments for a calculation without pagination. For very long routes (e.g. 1000+ segments), pagination will be considered.
- **Single-Instance Recompute Worker:** `DegradedTripRecomputeJob` is optimized for single-instance execution. While fully concurrent-safe across multiple nodes (enforced by PostgreSQL row locks and unique constraints), distributed leader election or `FOR UPDATE SKIP LOCKED` is omitted for simplicity.
- **Identity Key Generation:** JPA entities use `GenerationType.IDENTITY`, which disables Hibernate JDBC batch inserts on `saveAll()`. This is an accepted simplicity trade-off for prototyping and will be revisited if write throughput becomes a bottleneck in Batch 12 benchmarks.

## Status: Batch 12 (Benchmarking)

### Implemented vs Planned

| Feature / Requirement | Planned | Implemented | Status |
| :--- | :--- | :--- | :--- |
| **REST Controllers & DTOs** | Controllers in `api`, DTO records, no entities exposed | `TripController` and clean Java record DTOs with Bean Validation | Matched |
| **Multi-Tenancy Isolation** | Tenant scoping extracted from verified JWT, silent cross-tenant 404 | `CurrentUserArgumentResolver`, `findByIdAndTenantId`, cross-tenant 404 | Matched |
| **3-Phase Calculation Service** | Read Tx -> CPU/Routing (No Tx) -> Write Tx; no holding locks across HTTP | `CalculationService` + `TripDataPersistenceService` using separate beans | Matched |
| **Timeline Canonical Fingerprint** | Canonical SHA-256 binary hash over post-cleaning timeline, config & rule version | `TimelineFingerprint` (Double.doubleToLongBits, epoch sec+nano, lengths) | Matched |
| **Three-Layer Concurrency Control** | PESSIMISTIC_WRITE row lock, unique DB constraints, optimistic `@Version` | Handled via `TripRepository`, PostgreSQL unique constraints, `@Version` | Matched |
| **Idempotent Recompute** | Reuse existing calculation on identical data; 201 on new, 200 on reused | Phase 1 & Phase 3 re-check, `reused: true`, `reused: false` | Matched |
| **Invoice Closing & Audit Ledger** | `POST /close-invoice`, immutable billed baseline, signed delta adjustments | `closeInvoice` endpoint, `BillingAdjustment` ledger, signed deltas | Matched |
| **Automatic Recomputation Job** | Background worker recomputing `DEGRADED` trips when provider recovers | `DegradedTripRecomputeJob` with circuit breaker gate & thread-safe stats | Matched |
| **Rule Drift Golden Test** | Freeze 2026.1 rules and assert exact sequence, meters, reasons & total | `RuleDriftGuardGoldenTest` (exact meters & 100-run determinism) | Matched |
| **Security & Authentication** | Stateless JWT (HS256) via Nimbus, Spring Security, BCrypt, RBAC | `SecurityConfiguration`, `JwtService`, `AuthService`, role matrices | Matched |
| **Requirement Traceability** | Traceability matrix covering every rule in PROJECT_BRIEF | [`docs/REQUIREMENTS_TRACEABILITY.md`](docs/REQUIREMENTS_TRACEABILITY.md) (100% TESTED) | Matched |
| **JUnit 5 Tag Categorisation** | Organised into `unit`, `integration`, `concurrency`, `failure-injection` | Categorised all 38 test classes, documented in [`docs/TESTING.md`](docs/TESTING.md) | Matched |
| **Failure Injection & Flapping** | 9 failure scenarios: timeouts, provider dropouts, DB rollbacks, breaker flapping | `ComprehensiveFailureInjectionIntegrationTest` | Matched |
| **Randomised Invariant Tests** | 200 pseudo-random fixed seeds asserting 9 physical invariants | `RandomizedTripInvariantTest` (runs in ~1.0 s) | Matched |
| **Privacy Redaction Invariant** | Root log capture asserting zero coordinates, employee refs, passwords in logs | `SecurityAndPrivacyRegressionIntegrationTest` + DTO `toString()` redaction | Matched |
| **Concurrency Stress Testing** | 5 consecutive runs of 50-thread stampede & simultaneous calculation | `AdvancedConcurrencyIntegrationTest` (5/5 passed, 0 flakiness) | Matched |
| **Benchmark Suite (Level A & B)** | 10,000-trip throughput, percentiles, heap, GC, E2E REST + PostgreSQL | `EngineBenchmark`, `EndToEndBenchmark`, `BenchmarkRegressionGuardTest` | Matched |
| **Metrics & Observability** | Micrometer, Actuator, Prometheus metrics binding | **PLANNED (Batch 13)** | Planned |
