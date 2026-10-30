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

### 1. Engine Tests Only (Fast, Plain Java, No Docker Required)
The core mathematical and GPS cleaning engine is framework-free and runs without Docker or Spring context:

```powershell
.\mvnw.cmd test -Dtest="com.example.gpsengine.engine.*Test"
```

### 2. Full Test Suite (Requires Docker Desktop)
Runs all engine tests, the Spring Web slice test, and repository integration tests against a temporary Testcontainers PostgreSQL container:

```powershell
.\mvnw.cmd test
```

## Status: Batch 3 (GPS Data Cleaning & Plain-Java Engine)

### Implemented vs Planned

| Feature / Requirement | Planned | Implemented | Status |
| :--- | :--- | :--- | :--- |
| **Plain Java Engine** | Zero Spring/JPA dependencies in `engine` package | Pure Java 21 records and static utilities | Matched |
| **Haversine Calculator** | Great-circle distance with $R=6,371,000$ m | `HaversineCalculator` pure static function | Matched |
| **Deterministic Sort** | Timestamp ascending, accuracy asc (nulls last), lat, lon | Custom multi-level `Comparator` in `GpsCleaner` | Matched |
| **Deduplication** | Same timestamp + lat/lon rounded to 6 decimals; keep most accurate | `DuplicateKey(Instant, latE6, lonE6)` HashSet filter | Matched |
| **Low Accuracy Filter** | Accuracy > threshold dropped; == threshold kept; null kept | Configurable threshold (default 50m) | Matched |
| **Teleport Filter** | Implied speed > maxSpeed relative to *last accepted* point dropped | Unrounded `km / hours` check against limit (default 120 km/h) | Matched |
| **Zero-Time Teleport** | $\Delta t = 0$ with distance > 0 treated as infinite speed | Explicit zero-time distance check in pipeline | Matched |
| **Pipeline Order** | 1. Invalid coords -> 2. Sort -> 3. Deduplicate -> 4. Low accuracy -> 5. Teleport | Fixed deterministic order fully documented in code | Matched |
| **Config Bean** | Read properties and register `GpsCleaningConfig` bean in `config` layer | `GpsCleaningConfiguration` in `config` package | Matched |
