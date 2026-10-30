# Operations Runbook: What to Check at 3 AM

This runbook provides on-call engineers with rapid diagnosis, root cause analysis, and immediate remediation steps for the GPS Distance Reconstruction Engine.

All operational endpoints require an **ADMIN** JWT token, except `/actuator/health` which is public.

---

## 1. Quick Authentication for Troubleshooting

To query operational metrics, obtain an ephemeral admin token:

```bash
# Obtain Admin JWT Token
export ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "'"$SEED_PASSWORD"'"}' | jq -r .token)

# Verify Token
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics | jq .
```

*Note: Never log, store, or share the output of the token or `$SEED_PASSWORD`.*

---

## 2. "What to Check at 3 AM" Incident Triage Matrix

| Metric Name | Query Command (Admin Token) | Healthy Range | Symptom If Breached | Most Likely Root Cause | Immediate Remediation Action |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`gps.routing.breaker.state`** | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.routing.breaker.state \| jq '.measurements[0].value'` | `0.0` (CLOSED) | Gauge reads `2.0` (OPEN) or `1.0` (HALF_OPEN). Increased fallback straight-line calculations. | External routing provider (OSRM/Google Maps) 5xx errors, timeouts, or network partition. | 1. Check provider status page/health.<br>2. Inspect `gps.routing.provider.failures`.<br>3. In dev/demo, check `demo.fault-injection.enabled` and disable provider failure via `/api/v1/admin/demo/provider-failure`.<br>4. If provider is restored, allow breaker half-open probe after reset timeout (`routing.circuit-breaker.open-duration-seconds`). |
| **`gps.routing.breaker.transitions`** | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.routing.breaker.transitions \| jq '.measurements[0].value'` | Stable, 0 during steady state | Flapping transitions (rapid increment between CLOSED $\to$ OPEN $\to$ HALF_OPEN $\to$ OPEN). | Intermittent provider packet drops, unstable network latency, or throttling/rate limits. | 1. Increase provider timeout (`routing.timeout-seconds`).<br>2. Increase failure threshold (`routing.circuit-breaker.failure-rate-threshold`).<br>3. Switch to backup provider or enable route caching. |
| **`gps.degraded.trips`** | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.degraded.trips \| jq '.measurements[0].value'` | `< 100` trips (or near 0 during steady state) | Backlog monotonically growing; driver/vehicle trips calculated with straight-line fallback. | 1. Routing provider down during peak hours.<br>2. Scheduled recompute job disabled or failing (`recompute.enabled=false`). | 1. Verify `recompute.enabled=true` in environment.<br>2. Check `gps.recompute.runs` and `gps.recompute.last.success.age.seconds`.<br>3. Once routing provider is healthy, trigger or wait for recompute cycle (`recompute.interval-seconds`). |
| **`gps.degraded.oldest.age.seconds`** | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.degraded.oldest.age.seconds \| jq '.measurements[0].value'` | `< 7200` (under 2 hours) | Oldest degraded trip exceeds billing window ($> 86400$ s); invoices risk closing with straight-line estimates. | Recompute job starvation, large batch size blocking transaction, or repeated provider rejections. | 1. Verify DB query latency on `idx_trip_status`.<br>2. Reduce `recompute.batch-size` (e.g. from 100 to 25) to avoid long transactions.<br>3. Check if invoice close scheduler is running prematurely. |
| **`gps.routing.cache`** | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:8080/actuator/metrics/gps.routing.cache?tag=result:hit" \| jq '.measurements[0].value'` | Ratio $(\text{hits} / (\text{hits} + \text{misses})) > 0.40$ on common commuter routes | Hit rate drops to near 0; heavy surge in provider API latency and cost. | 1. Cache was evicted or sized too small (`routing.cache.maximum-size`).<br>2. Fleet dispatched on entirely novel geographic zones.<br>3. Coordinate rounding precision reduced. | 1. Inspect `gps.routing.cache.size`.<br>2. Increase cache capacity (`routing.cache.maximum-size=100000`).<br>3. Check cache TTL expiration (`routing.cache.expire-after-write-minutes`). |
| **`gps.billing.adjustments`** | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.billing.adjustments \| jq '.measurements[0].value'` | Predictable, tracks recovery of degraded trips | Sudden spike in billing adjustments with high delta distribution. | Mass recomputations after prolonged provider outage, or rule version mismatch. | 1. Inspect `gps.billing.adjustments.delta.abs.meters` to see magnitude of distance changes.<br>2. Verify rule version in calculation history (`RULE_VERSION`).<br>3. Verify whether invoices are OPEN or CLOSED. CLOSED invoices will reject automatic adjustments. |
| **`gps.concurrency.conflicts`** | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.concurrency.conflicts \| jq '.measurements[0].value'` | `< 1%` of calculation requests | Frequent 409 Conflict errors returned to clients or logged in background recompute. | Duplicate concurrent POST requests to `/calculations` or background job colliding with operator recompute. | 1. Verify client retry logic has exponential backoff and jitter.<br>2. Check if multiple application replicas are running without database row locking.<br>3. Ingestion pipeline may be retrying calculation triggers aggressively. |
| **`gps.auth.denied`** | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:8080/actuator/metrics/gps.auth.denied?tag=type:401" \| jq '.measurements[0].value'` | `< 10` per hour (accidental expirations) | Sharp spike in 401 (unauthenticated) or 403 (forbidden) responses. | 1. Expired JWT secret or misconfigured token issuer.<br>2. Credential stuffing or brute-force attack on `/api/v1/auth/login`.<br>3. Client service sending malformed Authorization header. | 1. Check client logs for clock skew.<br>2. Inspect rate of `gps.auth.logins?tag=result:failure`.<br>3. Check reverse proxy / ingress firewall to block offending source IPs. |
| **`gps.errors.unexpected`** | `curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.errors.unexpected \| jq '.measurements[0].value'` | `0.0` | Non-zero count indicates unhandled 500 Internal Server Errors in production. | Database pool exhaustion, unhandled NullPointerException, out-of-memory, or unexpected payload structure. | 1. Search logs for corresponding `correlationId`.<br>2. Check PostgreSQL connection pool utilization.<br>3. Check JVM heap memory via `/actuator/metrics/jvm.memory.used`. |

---

## 3. Detailed Incident Playbooks

### Playbook A: Circuit Breaker Tripped / External Provider Outage

**Symptoms:**
- `gps.routing.breaker.state` is `2.0` (OPEN).
- Logs show: `WARN c.e.g.r.c.CircuitBreaker - Circuit breaker opened: failure rate ...`
- Distance calculations complete successfully but with status `DEGRADED`.

**Verification:**
```bash
# Check breaker state
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.routing.breaker.state | jq .

# Check provider failure count
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.routing.provider.failures | jq .

# Check rejection count
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.routing.breaker.rejections | jq .
```

**Remediation:**
1. Check external provider reachability (e.g. OSRM daemon / Google Directions endpoint).
2. If in demo or test environment, check if fault injection was enabled:
   ```bash
   curl -s -X POST http://localhost:8080/api/v1/admin/demo/provider-failure \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"failing": false}'
   ```
3. Once provider connectivity is restored, the circuit breaker will automatically enter `HALF_OPEN` state after the configured timeout (`routing.circuit-breaker.open-duration-seconds`, default 60s) upon the next routing call.
4. If the provider remains unstable, straight-line fallback guarantees zero downtime and uninterrupted trip billing pipelines.

---

### Playbook B: Degraded Trip Backlog Growing

**Symptoms:**
- `gps.degraded.trips` $> 0$ and increasing.
- `gps.degraded.oldest.age.seconds` $> 3600$.

**Verification:**
```bash
# Backlog count
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.degraded.trips | jq .

# Age of oldest degraded trip in seconds
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.degraded.oldest.age.seconds | jq .

# Recompute job runs and successes
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.recompute.runs | jq .
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.recompute.recovered | jq .
```

**Remediation:**
1. Verify background scheduler is active: check application configuration `recompute.enabled=true`.
2. Inspect `gps.recompute.last.success.age.seconds`. If aging past the scheduled interval, the background thread may be blocked on a slow DB query.
3. Check PostgreSQL lock contention on `trips` table.
4. Operator manual recovery: An OPERATOR or ADMIN can trigger recalculation for individual degraded trips immediately via:
   ```bash
   curl -s -X POST http://localhost:8080/api/v1/trips/{tripId}/calculations \
     -H "Authorization: Bearer $OPERATOR_TOKEN"
   ```

---

### Playbook C: Routing Cache Hit Rate Collapse

**Symptoms:**
- Provider API costs or latencies spiking.
- Cache hit counter flatlines while `gps.routing.provider.calls` accelerates.

**Verification:**
```bash
# Cache hits
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:8080/actuator/metrics/gps.routing.cache?tag=result:hit" | jq .

# Cache misses
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:8080/actuator/metrics/gps.routing.cache?tag=result:miss" | jq .

# Current cache entry count
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.routing.cache.size | jq .
```

**Remediation:**
1. Check if application recently restarted (cache is in-memory and warms up on demand).
2. If cache size equals `maximum-size` (default 50,000), cache is thrashing due to high turnover. Increase `routing.cache.maximum-size` in environment variables:
   ```env
   ROUTING_CACHE_MAXIMUM_SIZE=200000
   ```
3. Restart application gracefully to apply expanded heap / cache size.

---

### Playbook D: Concurrency Conflicts on Trip Calculations

**Symptoms:**
- HTTP 409 Conflict responses returned to clients.
- `gps.concurrency.conflicts` incrementing.

**Verification:**
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/actuator/metrics/gps.concurrency.conflicts | jq .
```

**Remediation:**
1. Inspect client ingestion architecture: confirm clients are not firing multiple concurrent calculation triggers for the same trip.
2. Ingestion microservices must implement client-side debounce (e.g. triggering recalculation only after ping batching completes).
3. The engine uses optimistic locking (`@Version`) and database-level idempotency (`fingerprint` uniqueness) to guarantee correctness under concurrent calls. Retrying with exponential backoff resolves transient collisions.

---

## 4. Privacy & Operational Security Rules

During on-call triage and incident response:

1. **NEVER query or log coordinates or employee references:** The engine redacts latitude, longitude, and employee handles from all log output, error messages, and metrics.
2. **NEVER print authentication tokens or secrets:** Do not echo `$ADMIN_TOKEN`, `$JWT_SECRET`, or `$SEED_PASSWORD` into ticket comments, chat channels, or shared runbooks.
3. **Correlation ID Tracking:** When investigating a specific failed request, search logs by `correlationId` from the client response header `X-Correlation-Id`:
   ```bash
   docker logs gps-engine 2>&1 | grep "a1b2c3d4-..."
   ```

