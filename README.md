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

Ensure **Docker Desktop is running**, as integration tests spin up a dedicated, isolated PostgreSQL container via Testcontainers:

```powershell
.\mvnw.cmd test
```

## Status: Batch 2 (Entities, PostgreSQL & JPA Repositories)

### Implemented vs Planned

| Feature / Requirement | Planned | Implemented | Status |
| :--- | :--- | :--- | :--- |
| **Docker PostgreSQL** | Compose service on non-standard port | `postgres:17-alpine` on host port `5434` | Matched |
| **Environment Config** | `.env.example` committed, `.env` ignored | `.env.example` with `DB_PORT=5434`, `.env` git-ignored | Matched |
| **Domain Entities** | Only 6 entities (`Trip`, `GpsPing`, `TripMilestone`, `DistanceCalculation`, `TripSegment`, `BillingAdjustment`) | All 6 implemented with `tenantId`, `Instant` timestamps, and `long` metres | Matched |
| **Privacy & Security** | No coordinates or employee data in `toString()` | Clean `toString()` implementations omitting PII and coordinates | Matched |
| **Entity Relationships** | Only `@ManyToOne(fetch = LAZY)` from child to parent | Zero bidirectional `@OneToMany` collections | Matched |
| **Repositories** | Spring Data JPA interfaces with tenant & ordering queries | All 6 repositories with required query methods | Matched |
| **Repository Tests** | `@DataJpaTest` with real PostgreSQL via Testcontainers | 5 repository tests + 1 hello slice test passing on real Postgres container | Matched |
| **Test Database** | `@AutoConfigureTestDatabase(replace = NONE)` with `@ServiceConnection` | Configured with `testcontainers.version=1.21.4` | Matched |
