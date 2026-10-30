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
The core mathematical, GPS cleaning, anchor merging, and distance calculation engine is framework-free and runs without Docker or Spring context:

```powershell
.\mvnw.cmd test -Dtest="com.example.gpsengine.engine.*Test"
```

### 2. Full Test Suite (Requires Docker Desktop)
Runs all 50 tests (engine tests, web slice test, and repository integration tests against a temporary Testcontainers PostgreSQL container):

```powershell
.\mvnw.cmd test
```

## Rounding & Precision Trade-offs

- **Segment Distances:** Each segment distance calculation (aerial straight-line, parked zero, routed road distance, or 1.4 aerial fallback) is rounded to whole meters (`Math.round(distanceMeters)`).
- **Deterministic Sum:** The trip total distance is the exact mathematical sum of these whole-meter `long` values. This guarantees 100% deterministic, reproducible totals without floating-point accumulation drift or rounding discrepancies across platforms.
- **Micro-distance Trade-off:** Segments shorter than 0.5 meters (e.g. slight jitter while parked) round down to 0 meters. This minor micro-distance undercounting is an intentional trade-off in favor of audit-proof integer precision.

## Status: Batch 5 (Segment Distance Rules & Distance Engine)

### Implemented vs Planned

| Feature / Requirement | Planned | Implemented | Status |
| :--- | :--- | :--- | :--- |
| **Plain Java Engine** | Zero Spring/JPA/Web dependencies in `engine` and `routing` packages | `DistanceEngine`, `SegmentConfig`, `DistanceResult`, `SegmentResult`, `RoutingProvider` are pure Java | Matched |
| **Short Gap Aerial** | Gap < 70s and speed <= 120 km/h: straight-line distance | Evaluated first via Haversine calculation | Matched |
| **Parked Rule** | Gap >= 70s and speed < 5 km/h: 0m distance | Evaluated second; filters stops, signals, and dwell time | Matched |
| **Speed Pre-check Optimization** | If straight-line speed > 120 km/h, road distance cannot be valid | Rejects immediately without calling routing provider | Matched |
| **Routing Provider Fallback** | On provider error/timeout/exception: straight-line * 1.4 + mark trip DEGRADED | Fallback aerial 1.4 applied; `TripStatus.DEGRADED` set | Matched |
| **Distance Ceiling** | Routed road distance > 100 km: 0m distance | Rejected as `REJECTED_MAX_DISTANCE` | Matched |
| **Road Speed Rejection** | Both routed speed > 120 km/h AND straight*1.4 speed > 120 km/h: 0m distance | Evaluates dual speed condition before rejecting as `REJECTED_SPEED` | Matched |
| **Single Speed Configuration** | Unified 120 km/h ceiling across cleaning and distance engine | `SegmentConfiguration` binds to `gps.cleaning.max-speed-kmh` (120 km/h) | Matched |
| **Auditability & Privacy** | Explainable segment reasons and rule counts; no lat/lon in reasons or logs | Clear rule reasons, rule counters map, zero coordinate logging | Matched |
