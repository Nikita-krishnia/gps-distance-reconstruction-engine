# System Architecture & Technical Design

This document details the architectural decisions, component interactions, data model, failure modes, and correctness guarantees of the **GPS Distance Reconstruction Engine**.

---

## 1. High-Level Architecture

The engine is built around a resilient pipeline that accepts noisy, real-world GPS streams and driver milestones, performs cleaning and anchor synthesis, reconstructs trip segments via an external routing provider or straight-line fallback, caches spatial queries, and automatically recovers degraded trips when provider connectivity is restored.

```mermaid
flowchart TD
    Client["Client Applications<br>(Web / Mobile / Internal)"]
    
    subgraph Edge ["Security & Context Layer"]
        CorrFilter["CorrelationIdFilter<br>(X-Correlation-Id & MDC init)"]
        SecFilter["Spring Security Filter Chain<br>(Nimbus BearerTokenAuthentication)"]
        MdcFilter["SecurityMdcFilter<br>(MDC tenantId & userId)"]
    end
    
    subgraph API ["REST API Layer"]
        TripCtrl["TripController<br>(/api/v1/trips)"]
        AuthCtrl["AuthController<br>(/api/v1/auth)"]
        DemoCtrl["DemoFaultInjectionController<br>(/api/v1/admin/demo)"]
        Actuator["Spring Boot Actuator<br>(/actuator/health, /actuator/metrics)"]
    end
    
    subgraph DomainServices ["Application & Domain Services"]
        TripSvc["TripService<br>(Ingestion & Orchestration)"]
        CalcSvc["CalculationService<br>(Fingerprinting & Idempotency)"]
        AuthSvc["AuthService<br>(BCrypt strength 10 & JWT)"]
        PersistSvc["TripDataPersistenceService<br>(Isolated @Transactional units)"]
        RecomputeJob["DegradedTripRecomputeJob<br>(Scheduled Worker @ 30s)"]
    end
    
    subgraph EngineCore ["Pure Java Core Engine"]
        Cleaner["GpsCleaner<br>(Deduplication, Teleport drop, Jitter)"]
        Anchor["AnchorBuilder<br>(Milestone interpolation, Office snap)"]
        DistEngine["DistanceEngine<br>(Segment partitioning, Haversine fallback)"]
        Fingerprint["TimelineFingerprint<br>(Canonical SHA-256 IEEE 754)"]
    end
    
    subgraph RoutingResilience ["Routing & Resilience Tier"]
        Router["RoutingProvider<br>(OsrmRoutingProvider / FakeRoutingProvider)"]
        Cache["Spatial Route Cache<br>(Caffeine 0.001 deg grid cells)"]
        Breaker["CircuitBreaker<br>(Sliding window, OPEN/HALF-OPEN/CLOSED)"]
    end
    
    subgraph Storage ["Persistence Tier"]
        Postgres[(PostgreSQL 17 Database<br>Tenancy, Row Versioning, Indices)]
    end
    
    subgraph Telemetry ["Observability Tier"]
        GpsMetrics["GpsMetrics<br>(Micrometer 22+ custom counters/gauges/timers)"]
        StructuredLogs["Structured Logging<br>(ECS JSON console format)"]
    end

    Client --> CorrFilter --> SecFilter --> MdcFilter --> API
    TripCtrl --> TripSvc --> PersistSvc
    TripCtrl --> CalcSvc
    CalcSvc --> Cleaner --> Anchor --> Fingerprint --> DistEngine
    DistEngine --> Cache --> Breaker --> Router
    PersistSvc --> Postgres
    RecomputeJob --> CalcSvc
    CalcSvc --> PersistSvc
    CalcSvc --> GpsMetrics
    Router --> GpsMetrics
    Breaker --> GpsMetrics
    RecomputeJob --> GpsMetrics
    Edge -.-> StructuredLogs
    API -.-> StructuredLogs
    DomainServices -.-> StructuredLogs
```

---

## 2. Sequence Diagram: Resilient End-to-End Flow

The following sequence illustrates the engine's hardest operational workflow:
1. Operator uploads raw pings and milestones.
2. Calculation service cleans and synthesizes the timeline.
3. External routing provider fails; circuit breaker opens; trip calculates with straight-line fallback (`DEGRADED`).
4. Automatic recompute job detects degraded trip when provider recovers, recalculates road-network distance, and records an immutable `BillingAdjustment`.

```mermaid
sequenceDiagram
    autonumber
    actor Op as Operator / Ingestion
    participant API as TripController
    participant CS as CalculationService
    participant ENG as Pure Engine (Cleaner + Anchor)
    participant RE as RoutingEngine + Cache + Breaker
    participant DB as PostgreSQL
    participant JOB as DegradedTripRecomputeJob

    Op->>API: POST /api/v1/trips/{id}/calculations
    API->>CS: calculateTripDistance(tripId, tenantId)
    CS->>DB: Lock trip row (FOR UPDATE) & load pings + milestones
    CS->>ENG: Clean pings (dedup, teleport drop) & build anchors
    ENG-->>CS: Merged timeline & input fingerprint (SHA-256)
    CS->>RE: Reconstruct segments (from, to)
    Note over RE: External provider returns 503 / timeout.<br/>Circuit breaker trips OPEN.
    RE-->>CS: Fallback straight-line segment (rule=FALLBACK_AERIAL_1_4)
    CS->>DB: Save DistanceCalculation (status=DEGRADED, isCurrent=true)
    CS->>DB: Update Trip (status=DEGRADED, totalKm=12.400)
    CS-->>API: 201 Created (status=DEGRADED, totalKm=12.400, totalMeters=12400)
    API-->>Op: CalculationResponse (DEGRADED)

    Note over JOB,DB: Background scheduler fires every 30s
    JOB->>DB: Query oldest DEGRADED trips (idx_trip_status)
    JOB->>CS: recomputeDegradedTrip(tripId, tenantId)
    CS->>DB: Lock trip row (skip if locked or invoice closed)
    Note over RE: External provider restored.<br/>Circuit breaker probe succeeds (HALF_OPEN -> CLOSED).
    CS->>RE: Reconstruct segments
    RE-->>CS: Road-network geometry distance (OSRM)
    CS->>DB: Save new DistanceCalculation (status=CALCULATED, isCurrent=true)
    CS->>DB: Mark old calculation isCurrent=false
    CS->>DB: Insert BillingAdjustment (oldKm=12.400, newKm=14.100, delta=+1.700km)
    CS->>DB: Update Trip (status=CALCULATED, totalKm=14.100)
    JOB-->>DB: Commit recompute transaction
```

---

## 3. Failure Mode & Resilience Matrix

| Failure Mode | System Response | Recovery Mechanism | Verified In Test |
| :--- | :--- | :--- | :--- |
| **Routing Provider Outage / HTTP 5xx** | Circuit breaker opens; calls immediately fallback to straight-line Haversine distance multiplied by 1.4. | Calculation completes successfully with status `DEGRADED`; trip queued for recompute. | `FailureInjectionIntegrationTest#testExternalRoutingFailureTriggersCircuitBreakerAndDegradedStatus` |
| **Concurrent Calculation Requests** | Database optimistic locking (`@Version`) and row locking prevent double-calculation. Fingerprint check returns cached calculation if fingerprint matches. | Second concurrent request receives `reused=true` (HTTP 200) or clean 409 Conflict retry. | `AdvancedConcurrencyIntegrationTest#testConcurrentRecalculationsWithIdenticalFingerprint` |
| **Late Out-of-Order Pings Ingested (Open Trip)** | Re-calculation triggers fingerprint recompute. Fingerprint changes $\to$ new calculation created. | Prior calculation marked `isCurrent=false`; trip total updated atomically. | `LatePingsAndBillingAdjustmentIntegrationTest#latePingsAndAdjustmentIntegrity` |
| **Late Pings Ingested (Closed Invoice)** | Billed calculation remains locked and immutable. Re-calculation creates a new calculation and signed delta record. | Ledger records `BillingAdjustment` with signed `deltaMeters`; financial auditability guaranteed. | `LatePingsAndBillingAdjustmentIntegrationTest#latePingsAndAdjustmentIntegrity` |
| **High Jitter / Teleporting GPS Glitch** | Cleaner filters pings exceeding speed threshold ($> 120$ km/h) or low accuracy ($> 50$m). | Pings dropped prior to timeline synthesis; discarded count logged in metrics. | `GpsCleanerTest#testTeleportPingsAreDropped` |
| **Cross-Tenant Data Tampering** | All queries enforce `tenantId = :tenantId`. Mismatch throws 404 Resource Not Found. | Cross-tenant access impossible even for ADMIN users operating within a specific tenant context. | `SecurityRoleMatrixTest#crossTenantAccessIsBlocked` |
| **Database Connection Failure** | HikariCP pool handles reconnect; Spring transaction rolls back atomically. | HTTP 500 returned with correlationId in header and body; zero partial commits. | `ActuatorSecurityAndPrivacyIntegrationTest#logsContainNoPersonalDataOrSecrets` |

---

## 4. Data Model Summary

```mermaid
erDiagram
    TRIP ||--o{ GPS_PING : "has many"
    TRIP ||--o{ TRIP_MILESTONE : "has many"
    TRIP ||--o{ DISTANCE_CALCULATION : "has many"
    TRIP ||--o{ BILLING_ADJUSTMENT : "has many"
    DISTANCE_CALCULATION ||--o{ TRIP_SEGMENT : "contains"
    APP_USER }|--|| TENANT : "belongs to"

    TRIP {
        bigint id PK
        varchar tenant_id
        varchar status "NEW | CALCULATED | DEGRADED | FAILED"
        varchar billing_status "OPEN | CLOSED"
        double office_planned_lat
        double office_planned_lon
        numeric total_distance_km
        bigint version "Optimistic Locking"
        timestamp created_at
        timestamp updated_at
    }

    GPS_PING {
        bigint id PK
        bigint trip_id FK
        varchar tenant_id
        double latitude
        double longitude
        double accuracy_meters
        timestamp recorded_at
    }

    TRIP_MILESTONE {
        bigint id PK
        bigint trip_id FK
        varchar tenant_id
        varchar type "PICKUP | DROP | NO_SHOW | TRIP_START | TRIP_END"
        varchar employee_ref
        double actual_lat
        double actual_lon
        timestamp actual_time
    }

    DISTANCE_CALCULATION {
        bigint id PK
        bigint trip_id FK
        varchar tenant_id
        varchar input_hash "SHA-256 Fingerprint"
        varchar rule_version
        varchar status "CALCULATED | DEGRADED"
        bigint distance_meters
        numeric distance_km
        boolean is_current
        timestamp calculated_at
    }

    BILLING_ADJUSTMENT {
        bigint id PK
        bigint trip_id FK
        varchar tenant_id
        bigint old_calculation_id FK
        bigint new_calculation_id FK
        numeric old_distance_km
        numeric new_distance_km
        numeric delta_km
        varchar reason
        timestamp created_at
    }
```

### Key Performance Indices
- `idx_trip_tenant_status`: Compound index on `(tenant_id, status)` for fast operator filtering.
- `idx_trip_status`: Dedicated index on `trip.status` enabling high-throughput polling by `DegradedTripRecomputeJob` without full-table scans.
- `uk_calc_trip_hash_ver_status`: Unique constraint preventing duplicate calculations for identical inputs.

---

## 5. Caching Staleness & Invalidation Guarantee

The spatial route cache operates under a mathematical immutability guarantee:

1. **Deterministic Grid Quantization:**
   Coordinate pairs are rounded to fixed spatial grid cells:
   $$\text{cell}(\text{lat}, \text{lon}) = \left(\text{round}\left(\frac{\text{lat}}{\Delta}\right) \times \Delta, \; \text{round}\left(\frac{\text{lon}}{\Delta}\right) \times \Delta\right)$$
   Where $\Delta = 0.001^\circ \approx 111$ meters.

2. **Immutable Physical Distance:**
   The road-network driving distance between two static physical locations on Earth is time-invariant over operational time windows (hours to days). Road networks do not physically move.

3. **Zero Staleness Guarantee:**
   Because cache keys depend solely on quantized geometric coordinates and vehicle routing profiles—never on timestamps, driver IDs, or trip state—a cached distance for segment $(A, B)$ can **never become stale** or incorrect for any future trip traversing the same spatial corridor.

