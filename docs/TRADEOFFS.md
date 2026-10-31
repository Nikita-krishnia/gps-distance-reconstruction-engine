# Architectural Trade-Offs & Design Decisions

This document articulates the engineering trade-offs made in designing the **GPS Distance Reconstruction Engine**, evaluating the balances between consistency, availability, latency, complexity, and operational maintainability.

---

## 1. Consistency vs. Availability vs. Latency (CAP & PACELC)

### A. Distance Reconstruction: Availability over Pure Consistency
- **Decision:** When the primary routing provider (e.g. OSRM, Google Directions) experiences latency spikes or outages, the engine trips a circuit breaker and falls back to **straight-line Haversine distance multiplied by a 1.4 detour factor**, completing the trip calculation with status `DEGRADED`.
- **Trade-off:**
  - *Gained:* High availability and predictable sub-second response times. Client apps, driver checkout flows, and operational dashboards never hang or encounter 503 errors during upstream provider outages.
  - *Compromised:* Immediate road-network geometric precision. Degraded trips report straight-line distance temporarily until the provider recovers and the background recompute worker recalculates the actual route.
- **Remediation:** Eventual consistency is guaranteed by `DegradedTripRecomputeJob`, which continually recovers degraded trips once the circuit breaker re-closes.

### B. Billing & Invoicing: Strict Consistency over Ingestion Mutability
- **Decision:** Once an invoice is closed (`CLOSED`), the originally billed calculation row and distance are mathematically immutable. Any subsequent calculation triggered by late-arriving GPS pings or degraded route recovery is preserved as a new `DistanceCalculation` and recorded in an append-only `BillingAdjustment` ledger with a signed delta (e.g. `+1.800 km`). The original billed amount is never silently overwritten.
- **Trade-off:**
  - *Gained:* Financial auditability, non-destructive ledgering, zero silent billing discrepancies, and strict compliance with accounting ledger standards.
  - *Compromised:* Downstream payroll and vendor disbursement pipelines must query the `BillingAdjustment` ledger (`GET /api/v1/trips/{id}/billing-adjustments`) to process secondary settlements.
- **Justification:** In enterprise transportation billing, financial finality and immutable audit logs take precedence over in-place record mutations.

---

## 2. In-Memory Caching (Caffeine) vs. Distributed Caching (Redis)

- **Decision:** Adopted an in-memory Caffeine cache with bounded size ($50,000$ entries) and LRU eviction, keyed by quantized coordinate cells ($\approx 111$ meters).
- **Evaluation:**
  | Dimension | In-Memory (Caffeine) [Chosen] | Distributed (Redis) |
  | :--- | :--- | :--- |
  | **Lookup Latency** | $< 0.1$ microseconds (direct pointer dereference) | $1.0 - 5.0$ milliseconds (network hop + serialization) |
  | **Operational Overhead** | Zero (runs inside JVM process) | Requires managing Redis cluster, sentinel/sentinel HA, network ACLs |
  | **Failure Modes** | Bounded heap memory; no network failure possible | Network timeouts, Redis failovers, connection pool exhaustion |
  | **Horizontal Scaling** | Independent cache per pod; cold cache on startup | Shared cache across all pods; instant cache warming |
- **Conclusion:** Because commuter fleet routes are concentrated along primary urban transit corridors, a local node warms its working set within minutes. Avoiding the latency and operational burden of an external Redis cluster preserves architectural simplicity and sub-millisecond calculation speeds.

---

## 3. Synchronous REST Calculation vs. Asynchronous Message Queue (Kafka/RabbitMQ)

- **Decision:** Distance calculations requested by operators are executed **synchronously** via REST (`POST /api/v1/trips/{id}/calculations`), returning immediate results. Automatic recovery of degraded trips is handled by an **asynchronous scheduled worker** (`DegradedTripRecomputeJob`).
- **Trade-off:**
  - *Gained:* Real-time, deterministic feedback for operators and dispatchers. No queue lag, dead-letter queues, or complex polling/webhook mechanisms needed for standard operational workflows.
  - *Compromised:* Heavy ingestion bursts (e.g. 5,000 simultaneous calculation requests) directly consume HTTP worker threads and database connections.
- **Mitigation:** Database row locking (`SELECT ... FOR UPDATE SKIP LOCKED`), optimistic locking (`@Version`), and input fingerprint deduplication (`TimelineFingerprint`) prevent duplicate concurrent computations.

---

## 4. What Was Deliberately Left Out (Premature Complexity Avoidance)

In accordance with sound systems engineering principles, several common patterns were deliberately avoided:

1. **No External Distributed Task Schedulers (e.g. Quartz / Airflow):**
   Spring's `@Scheduled` with pessimistic database queries (`findOldestCreatedAtByStatus` with `idx_trip_status`) adequately handles recurring recovery without coordinating multi-cluster scheduler locks.

2. **No Third-Party JWT or Auth Libraries (e.g. JJWT, Auth0 SDK):**
   Utilized Spring Boot's built-in `spring-boot-starter-oauth2-resource-server` and Nimbus JOSE. This eliminates third-party supply-chain dependencies while providing strict HMAC-SHA256 signature verification.

3. **No Heavy Distributed Tracing Infrastructure (e.g. OpenTelemetry / Jaeger):**
   Standardized `X-Correlation-Id` propagation, SLF4J MDC, and Elastic Common Schema (ECS) JSON logging provide complete, zero-overhead traceability across all HTTP and background operations without requiring an external tracing agent daemon.

4. **No Heavyweight GIS / PostGIS Dependency:**
   All core geospatial cleaning, Haversine computations, and spatial grid cell quantizations are implemented in **pure Java** (`java.lang.Math`). This eliminates PostGIS extension dependencies, keeping database migrations fast, portable, and runnable on vanilla PostgreSQL.

