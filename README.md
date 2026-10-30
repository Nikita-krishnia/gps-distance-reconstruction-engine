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
The core mathematical, GPS cleaning, and anchor merging engine is framework-free and runs without Docker or Spring context:

```powershell
.\mvnw.cmd test -Dtest="com.example.gpsengine.engine.*Test"
```

### 2. Full Test Suite (Requires Docker Desktop)
Runs all 32 tests (engine tests, web slice test, and repository integration tests against a temporary Testcontainers PostgreSQL container):

```powershell
.\mvnw.cmd test
```

## Status: Batch 4 (Anchor Points & Timeline Merging)

### Implemented vs Planned

| Feature / Requirement | Planned | Implemented | Status |
| :--- | :--- | :--- | :--- |
| **Plain Java Engine** | Zero Spring/JPA dependencies in `engine` anchor classes | `AnchorBuilder` and `TripTimelineMerger` are pure Java | Matched |
| **Employee Anchors** | Actual if valid; else ad-hoc profile fallback or planned fallback; strict isolation | Priority fallback with strict ad-hoc/planned segregation | Matched |
| **No-Show Anchors** | Behind `includeNoShow` config; actual then planned | Configurable no-show anchor synthesis | Matched |
| **Office Anchor** | Actual if within 400m radius of planned office; else planned office | Great-circle Haversine comparison with 400m boundary tolerance | Matched |
| **Post-Cleaning Anchors** | Anchors added after GPS cleaning so never dropped by teleport filter | Anchors synthesized independently and merged into timeline | Matched |
| **Deterministic Timeline Merge** | Sort by time; tie-break: GPS before Anchor, anchorSource enum order, lat, lon | Canonical `TIMELINE_COMPARATOR` ensuring 100% determinism | Matched |
| **Privacy by Design** | No employee refs or coordinates in `toString()` | Clean `toString()` across all domain and engine models | Matched |
| **Database Entity Update** | Add nullable `profileSignInTime` to `TripMilestone` | Added field, getter, constructor, maintaining tests | Matched |
| **Spring Configuration** | Register `AnchorConfig` bean reading `gps.anchors.*` properties | `AnchorConfiguration` in `config` layer | Matched |
