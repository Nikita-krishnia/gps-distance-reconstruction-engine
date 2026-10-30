package com.example.gpsengine.engine;

import com.example.gpsengine.domain.SegmentRule;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.routing.RouteResult;
import com.example.gpsengine.routing.RoutingProvider;
import com.example.gpsengine.routing.RoutingUnavailableException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic distance reconstruction engine that iterates across the chronological
 * trip timeline, evaluates each consecutive pair against business rules, and tallies
 * the billable total metres.
 *
 * PIPELINE ORDER & RULE HEURISTICS (applied to each consecutive pair):
 * 1. SHORT_GAP_AERIAL: Time gap < 70s AND implied straight speed <= maxSpeedKmh.
 *    Assumes direct straight-line travel (Haversine).
 * 2. PARKED: Time gap >= 70s AND straight speed < 5 km/h.
 *    Vehicle was stationary or crawling in traffic; uses straight-line displacement.
 * 3. Speed Pre-Check: If straight-line speed > maxSpeedKmh, immediately reject as REJECTED_SPEED
 *    with 0 metres. A road path can never be shorter than a straight line, so querying
 *    the routing provider would be a guaranteed waste of resources.
 * 4. ROUTED (Provider Query):
 *    a. If provider throws, times out, or returns invalid data (null, NaN, inf, <0):
 *       use FALLBACK_AERIAL_1_4 (straightMeters * 1.4) and mark the trip as DEGRADED.
 *    b. If routedMeters > maxRouteMeters (100 km): REJECTED_MAX_DISTANCE, 0 metres.
 *    c. If routedSpeedKmh > maxSpeedKmh AND (straightMeters * 1.4 speed) > maxSpeedKmh:
 *       REJECTED_SPEED, 0 metres. Both must exceed limit to reject.
 *    d. Otherwise: accept as ROUTED with routedMeters.
 *
 * UNITS & ROUNDING:
 * - Calculations performed in double: distance in metres, time in milliseconds converted to hours, speed in km/h.
 * - Each segment is rounded ONCE to whole metres using Math.round() and stored as a long.
 * - Trip totalMeters is the exact sum of these long segment metres, avoiding floating-point drift.
 * - Reasons NEVER log or format latitude/longitude coordinates to protect data privacy.
 *
 * Time Complexity: O(N) where N is the number of points on the timeline.
 * Space Complexity: O(N) for storing the generated SegmentResult entries.
 */
public class DistanceEngine {

    public static final String RULE_VERSION = "2026.1";

    /**
     * Reconstructs billable distance across the trip timeline.
     *
     * @param timeline the chronological timeline of TripPoints
     * @param provider the routing provider (or mock/stub)
     * @param config segment configuration thresholds and limits
     * @return DistanceResult with individual segment breakdown and total billable metres
     */
    public DistanceResult calculate(List<TripPoint> timeline, RoutingProvider provider, SegmentConfig config) {
        if (timeline == null || timeline.size() < 2) {
            return DistanceResult.empty(RULE_VERSION);
        }

        SegmentConfig activeConfig = config != null ? config : SegmentConfig.defaults();

        List<SegmentResult> segments = new ArrayList<>(timeline.size() - 1);
        Map<SegmentRule, Integer> ruleCounts = new EnumMap<>(SegmentRule.class);
        long totalMeters = 0L;
        boolean degraded = false;
        int providerCalls = 0;
        int cacheHits = 0;
        int cacheMisses = 0;

        for (int i = 0; i < timeline.size() - 1; i++) {
            TripPoint from = timeline.get(i);
            TripPoint to = timeline.get(i + 1);
            int sequenceNumber = i + 1;

            long dtMillis = Duration.between(from.timestamp(), to.timestamp()).toMillis();
            double dtSeconds = (double) dtMillis / 1000.0;
            // Convert milliseconds to hours for speed calculation: dtMillis / (1000 * 3600)
            double dtHours = (double) dtMillis / 3_600_000.0;

            double straightMeters = HaversineCalculator.distanceMeters(
                    from.latitude(), from.longitude(), to.latitude(), to.longitude()
            );

            double straightSpeedKmh;
            if (dtMillis <= 0) {
                straightSpeedKmh = straightMeters > 0.0 ? Double.POSITIVE_INFINITY : 0.0;
            } else {
                straightSpeedKmh = (straightMeters / 1000.0) / dtHours;
            }

            SegmentRule rule;
            double computedMeters;
            String reason;

            // Rule 1: SHORT_GAP_AERIAL
            if (dtSeconds < activeConfig.shortGapSeconds() && straightSpeedKmh <= activeConfig.maxSpeedKmh()) {
                rule = SegmentRule.SHORT_GAP_AERIAL;
                computedMeters = straightMeters;
                reason = String.format("Short gap (%.1fs < %ds) at plausible speed (%.1f km/h <= %.1f km/h); straight-line aerial used",
                        dtSeconds, activeConfig.shortGapSeconds(), straightSpeedKmh, activeConfig.maxSpeedKmh());
            }
            // Rule 2: PARKED
            else if (dtSeconds >= activeConfig.shortGapSeconds() && straightSpeedKmh < activeConfig.parkedSpeedKmh()) {
                rule = SegmentRule.PARKED;
                computedMeters = straightMeters;
                reason = String.format("Long gap (%.1fs >= %ds) with speed (%.2f km/h < %.1f km/h) below parked threshold; straight-line aerial used",
                        dtSeconds, activeConfig.shortGapSeconds(), straightSpeedKmh, activeConfig.parkedSpeedKmh());
            }
            // Rule 3: Speed Pre-Check (saves provider cost)
            else if (straightSpeedKmh > activeConfig.maxSpeedKmh()) {
                rule = SegmentRule.REJECTED_SPEED;
                computedMeters = 0.0;
                reason = String.format("Straight-line speed (%.1f km/h > %.1f km/h) exceeds speed limit; rejected without provider query",
                        straightSpeedKmh, activeConfig.maxSpeedKmh());
            }
            // Rule 4: ROUTED Path
            else {
                RouteResult routeResult = null;
                boolean providerFailed = false;

                try {
                    if (provider != null) {
                        routeResult = provider.route(from.latitude(), from.longitude(), to.latitude(), to.longitude());
                    } else {
                        providerFailed = true;
                    }
                } catch (com.example.gpsengine.routing.CircuitOpenException e) {
                    // Breaker rejected without touching upstream provider; neither providerCall nor cacheHit
                    providerFailed = true;
                } catch (RoutingUnavailableException | RuntimeException e) {
                    // Upstream provider attempt occurred and failed
                    providerCalls++;
                    providerFailed = true;
                }

                if (!providerFailed && routeResult != null) {
                    if (routeResult.source() == com.example.gpsengine.routing.RouteSource.CACHE) {
                        cacheHits++;
                    } else {
                        cacheMisses++;
                        providerCalls++;
                    }
                }

                if (providerFailed || routeResult == null
                        || Double.isNaN(routeResult.distanceMeters())
                        || Double.isInfinite(routeResult.distanceMeters())
                        || routeResult.distanceMeters() < 0.0) {
                    // Rule 4.a: Provider unavailable / malformed response
                    rule = SegmentRule.FALLBACK_AERIAL_1_4;
                    computedMeters = straightMeters * activeConfig.fallbackMultiplier();
                    degraded = true;
                    reason = String.format("Routing provider unavailable; fallback to straight-line aerial x %.1f",
                            activeConfig.fallbackMultiplier());
                } else {
                    double routedMeters = routeResult.distanceMeters();
                    if (routedMeters > activeConfig.maxRouteMeters()) {
                        // Rule 4.b: Max distance limit exceeded
                        rule = SegmentRule.REJECTED_MAX_DISTANCE;
                        computedMeters = 0.0;
                        reason = String.format("Routed distance (%.0fm > %.0fm) exceeds maximum route limit",
                                routedMeters, activeConfig.maxRouteMeters());
                    } else {
                        double routedSpeedKmh = (routedMeters / 1000.0) / dtHours;
                        double straight14SpeedKmh = ((straightMeters * activeConfig.fallbackMultiplier()) / 1000.0) / dtHours;

                        if (routedSpeedKmh > activeConfig.maxSpeedKmh() && straight14SpeedKmh > activeConfig.maxSpeedKmh()) {
                            // Rule 4.c: Speed rejection (both routed speed and straight*1.4 speed exceed limit)
                            rule = SegmentRule.REJECTED_SPEED;
                            computedMeters = 0.0;
                            reason = String.format("Routed speed (%.1f km/h) and straight x 1.4 speed (%.1f km/h) both exceed limit (%.1f km/h)",
                                    routedSpeedKmh, straight14SpeedKmh, activeConfig.maxSpeedKmh());
                        } else {
                            // Rule 4.d: Valid routed segment
                            rule = SegmentRule.ROUTED;
                            computedMeters = routedMeters;
                            reason = String.format("Routing provider returned valid road distance (%.0fm) at plausible speed (%.1f km/h)",
                                    routedMeters, routedSpeedKmh);
                        }
                    }
                }
            }

            // Units & Rounding: round each segment once to whole metres with Math.round()
            long segmentMeters = (rule == SegmentRule.REJECTED_SPEED || rule == SegmentRule.REJECTED_MAX_DISTANCE)
                    ? 0L
                    : Math.round(computedMeters);

            totalMeters += segmentMeters;
            ruleCounts.merge(rule, 1, Integer::sum);

            segments.add(new SegmentResult(
                    sequenceNumber,
                    from,
                    to,
                    segmentMeters,
                    rule,
                    reason
            ));
        }

        TripStatus tripStatus = degraded ? TripStatus.DEGRADED : TripStatus.CALCULATED;

        return new DistanceResult(
                Collections.unmodifiableList(segments),
                totalMeters,
                tripStatus,
                RULE_VERSION,
                providerCalls,
                cacheHits,
                cacheMisses,
                ruleCounts
        );
    }
}

