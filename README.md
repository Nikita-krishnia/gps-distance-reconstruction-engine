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
The core mathematical, GPS cleaning, anchor merging, distance calculation engine, and routing providers run without Docker or Spring context:

```powershell
# Engine tests (44 tests)
.\mvnw.cmd test -Dtest="com.example.gpsengine.engine.*Test"

# Routing tests (20 tests, 1 optional live test skipped by default)
.\mvnw.cmd test -Dtest="com.example.gpsengine.routing.*Test"
```

### 2. Full Test Suite (Requires Docker Desktop)
Runs all 70 tests (engine tests, routing tests, web slice test, and repository integration tests against a temporary Testcontainers PostgreSQL container):

```powershell
.\mvnw.cmd test
```

## Routing Providers

The system supports pluggable routing implementations chosen at startup via the `routing.provider` property:

1. **`fake` (Default):**
   - Calculates distance as Haversine straight-line distance multiplied by a configurable `detourFactor` (default `1.3`).
   - Completely deterministic, in-memory, and provides thread-safe call tracking and optional failure/latency injection.
   - Used for all automated unit tests, integration tests, and performance benchmarks to ensure reliable, zero-flakiness builds without internet or external server dependencies.

2. **`osrm`:**
   - Calls an external OSRM-compatible HTTP server using the standard driving route endpoint:
     `GET {baseUrl}/route/v1/driving/{fromLon},{fromLat};{toLon},{toLat}?overview=false`
   - **Coordinate Order:** OSRM mandates **longitude first**, then latitude (`{lon},{lat}`). Coordinates are formatted using `Locale.ROOT` with dot decimal separators.
   - Extracts road distance in meters from `routes[0].distance`.
   - Maps every network, timeout, HTTP, and JSON payload error into a clean `RoutingUnavailableException` with coordinate-free and secret-free messages.
   - Restores thread interruption status before throwing on interrupted requests.

### Configuration & Security
- **Safe Environment Variables:** Do not hardcode URLs or credentials in source code. Configure them in `.env` (kept git-ignored):
  ```bash
  OSRM_BASE_URL=http://localhost:5000
  OSRM_API_KEY=your_optional_api_key
  ```
- **Demo Server Limitation:** The public OSRM server (`https://router.project-osrm.org`) is for light demos only. It is heavily rate-limited and must never be used for benchmarks or automated CI pipelines.
- **Docker Compose Note:** OSRM is intentionally omitted from `compose.yaml` because OSRM requires multi-gigabyte OpenStreetMap extract files and pre-processing graphs.
- **Production Verification Status:** While the OSRM client has been rigorously tested against local HTTP servers simulating all success, error, timeout, and privacy edge cases, a real OSRM provider has **not** been verified under production-scale traffic conditions.

## Rounding & Precision Trade-offs

- **Segment Distances:** Each segment distance calculation (aerial straight-line, parked zero, routed road distance, or 1.4 aerial fallback) is rounded to whole meters (`Math.round(distanceMeters)`).
- **Deterministic Sum:** The trip total distance is the exact mathematical sum of these whole-meter `long` values. This guarantees 100% deterministic, reproducible totals without floating-point accumulation drift or rounding discrepancies across platforms.
- **Micro-distance Trade-off:** Segments shorter than 0.5 meters (e.g. slight jitter while parked) round down to 0 meters. This minor micro-distance undercounting is an intentional trade-off in favor of audit-proof integer precision.

## Status: Batch 6 (Routing Provider Implementations)

### Implemented vs Planned

| Feature / Requirement | Planned | Implemented | Status |
| :--- | :--- | :--- | :--- |
| **Fake Routing Provider** | Deterministic straight-line * detourFactor (default 1.3), AtomicInteger counter, failure/latency injection | `FakeRoutingProvider` with thread-safe counters and configurable fault simulation | Matched |
| **OSRM Routing Provider** | HTTP client querying OSRM driving endpoint; longitude first; timeout handling | `OsrmRoutingProvider` with shared `HttpClient`, `Locale.ROOT` coordinate formatting, and timeout management | Matched |
| **Error Normalization** | Map all HTTP/JSON/network/timeout errors to `RoutingUnavailableException` without leaking secrets or coords | Strict error translation for status codes, invalid JSON, missing routes, and negative/NaN distances | Matched |
| **Thread Interruption** | Restore interrupt flag before throwing on interrupted HTTP requests | `Thread.currentThread().interrupt()` called with simple English explanation comment | Matched |
| **API Key Privacy** | Bearer token header if key present; key never in logs, errors, or `toString()` | Safe header attachment; excluded from `toString()` and error messages | Matched |
| **URL Validation** | Validate `http://` or `https://` prefix; strip trailing slashes; warn on remote HTTP | Startup validation and remote HTTP logging warning | Matched |
| **Conditional Configuration** | Wire single bean via `@ConditionalOnProperty`; fail fast on unknown provider | `RoutingConfiguration` with `@PostConstruct` fail-fast validation | Matched |
| **Privacy by Design** | No coordinates, full URLs, or keys in exceptions or logs | Zero coordinates or sensitive values in logs or exception messages | Matched |
| **Live Smoke Test** | Optional live test skipped by default behind `OSRM_LIVE_TEST=true` | `OsrmLiveSmokeTest` guarded by `@EnabledIfEnvironmentVariable` (skipped by default) | Matched |
