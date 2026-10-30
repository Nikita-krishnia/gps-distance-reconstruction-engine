package com.example.gpsengine.engine;

import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.RiderKind;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Builds reliable anchor points from business milestones (employee pickups, drops,
 * no-shows, and trip destination office).
 *
 * DESIGN NOTE:
 * Anchors are synthesized and merged into the timeline AFTER GPS cleaning is complete.
 * This guarantees that business-critical anchor fixes are never eliminated by the
 * GPS teleport speed filter. In Batch 5, downstream segment rules evaluate the
 * resulting segments (e.g., assigning REJECTED_SPEED or ROUTED).
 *
 * PIPELINE ORDER & POLICY RULES:
 * 1. Location & Time Validity:
 *    - Latitude [-90..90], Longitude [-180..180], finite, not exactly (0,0).
 *    - Time must fall within the [tripStart, tripEnd] window (inclusive).
 * 2. Employee Anchors (PICKUP / DROP):
 *    - Priority 1: Actual location & actual time (EMPLOYEE_ACTUAL).
 *    - Priority 2 (AD_HOC rider): Profile location & profile sign-in time (EMPLOYEE_PROFILE_FALLBACK).
 *    - Priority 3 (PLANNED rider): Planned location & planned time (EMPLOYEE_PLANNED_FALLBACK).
 *    - Ad-hoc riders never fall back to planned; planned riders never fall back to profile.
 *    - If no valid location/time exists, skip and increment skippedNoValidLocation.
 * 3. No-Show Anchors (when config.includeNoShow == true):
 *    - Priority 1: Actual location & actual time (NO_SHOW_ACTUAL).
 *    - Priority 2: Planned location & planned time (NO_SHOW_PLANNED).
 *    - If neither valid, skip and increment skippedNoValidLocation.
 * 4. Office Anchor:
 *    - If actualEndLat/Lon is valid AND within officeRadiusMeters (Haversine <= radius):
 *      create OFFICE_ACTUAL at actual coords with tripEnd time.
 *    - Otherwise: create OFFICE_PLANNED at planned office coords with tripEnd time.
 *    - No trip-start anchor is synthesized (intentional design decision: start is anchored by GPS).
 *
 * Time Complexity: O(m log m) where m is the number of milestones due to deterministic pre-sorting.
 * Space Complexity: O(m) auxiliary space for the sorted copy and synthesized anchor points.
 */
public class AnchorBuilder {

    public AnchorResult buildAnchors(
            List<MilestoneInput> milestones,
            Instant tripStart,
            Instant tripEnd,
            Double actualEndLat,
            Double actualEndLon,
            double plannedOfficeLat,
            double plannedOfficeLon,
            AnchorConfig config
    ) {
        if (tripEnd == null) {
            throw new IllegalArgumentException("tripEnd must not be null");
        }

        AnchorConfig activeConfig = config != null ? config : AnchorConfig.defaults();

        int actualUsed = 0;
        int profileFallbackUsed = 0;
        int plannedFallbackUsed = 0;
        int noShowAdded = 0;
        int skippedNoValidLocation = 0;
        boolean officeActualUsed = false;

        List<TripPoint> anchors = new ArrayList<>();

        // Sort milestones deterministically: sortTime asc (nulls last), milestone type, employeeRef asc
        List<MilestoneInput> sortedMilestones = new ArrayList<>(milestones != null ? milestones : List.of());
        sortedMilestones.sort(Comparator
                .comparing(MilestoneInput::sortTime, Comparator.nullsLast(Instant::compareTo))
                .thenComparing(MilestoneInput::type)
                .thenComparing(MilestoneInput::employeeRef, Comparator.nullsLast(String::compareTo))
        );

        for (MilestoneInput m : sortedMilestones) {
            if (m.type() == MilestoneType.PICKUP || m.type() == MilestoneType.DROP) {
                // Rule B.1: Check actual location & time
                if (isValidLocation(m.actualLat(), m.actualLon()) && isValidTime(m.actualTime(), tripStart, tripEnd)) {
                    anchors.add(TripPoint.fromAnchor(m.actualLat(), m.actualLon(), m.actualTime(), AnchorSource.EMPLOYEE_ACTUAL));
                    actualUsed++;
                }
                // Rule B.2: Ad-hoc rider profile fallback
                else if (m.riderKind() == RiderKind.AD_HOC
                        && isValidLocation(m.profileLat(), m.profileLon())
                        && isValidTime(m.profileSignInTime(), tripStart, tripEnd)) {
                    anchors.add(TripPoint.fromAnchor(m.profileLat(), m.profileLon(), m.profileSignInTime(), AnchorSource.EMPLOYEE_PROFILE_FALLBACK));
                    profileFallbackUsed++;
                }
                // Rule B.3: Planned rider planned fallback
                else if (m.riderKind() == RiderKind.PLANNED
                        && isValidLocation(m.plannedLat(), m.plannedLon())
                        && isValidTime(m.plannedTime(), tripStart, tripEnd)) {
                    anchors.add(TripPoint.fromAnchor(m.plannedLat(), m.plannedLon(), m.plannedTime(), AnchorSource.EMPLOYEE_PLANNED_FALLBACK));
                    plannedFallbackUsed++;
                }
                // Rule B.4: Skip when no valid location/time found
                else {
                    skippedNoValidLocation++;
                }
            } else if (m.type() == MilestoneType.NO_SHOW) {
                // Rule C: Only include no-show if configured
                if (activeConfig.includeNoShow()) {
                    if (isValidLocation(m.actualLat(), m.actualLon()) && isValidTime(m.actualTime(), tripStart, tripEnd)) {
                        anchors.add(TripPoint.fromAnchor(m.actualLat(), m.actualLon(), m.actualTime(), AnchorSource.NO_SHOW_ACTUAL));
                        noShowAdded++;
                    } else if (isValidLocation(m.plannedLat(), m.plannedLon()) && isValidTime(m.plannedTime(), tripStart, tripEnd)) {
                        anchors.add(TripPoint.fromAnchor(m.plannedLat(), m.plannedLon(), m.plannedTime(), AnchorSource.NO_SHOW_PLANNED));
                        noShowAdded++;
                    } else {
                        skippedNoValidLocation++;
                    }
                }
            }
        }

        // Rule D: Office Anchor synthesis
        if (isValidLocation(actualEndLat, actualEndLon)) {
            double distanceToPlanned = HaversineCalculator.distanceMeters(
                    actualEndLat, actualEndLon, plannedOfficeLat, plannedOfficeLon
            );
            // Exactly equal to the radius is counted as inside
            if (distanceToPlanned <= activeConfig.officeRadiusMeters()) {
                anchors.add(TripPoint.fromAnchor(actualEndLat, actualEndLon, tripEnd, AnchorSource.OFFICE_ACTUAL));
                officeActualUsed = true;
            } else {
                anchors.add(TripPoint.fromAnchor(plannedOfficeLat, plannedOfficeLon, tripEnd, AnchorSource.OFFICE_PLANNED));
                officeActualUsed = false;
            }
        } else {
            anchors.add(TripPoint.fromAnchor(plannedOfficeLat, plannedOfficeLon, tripEnd, AnchorSource.OFFICE_PLANNED));
            officeActualUsed = false;
        }

        return new AnchorResult(
                anchors,
                actualUsed,
                profileFallbackUsed,
                plannedFallbackUsed,
                noShowAdded,
                skippedNoValidLocation,
                officeActualUsed
        );
    }

    private static boolean isValidLocation(Double lat, Double lon) {
        if (lat == null || lon == null) {
            return false;
        }
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            return false;
        }
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            return false;
        }
        // Exactly (0.0, 0.0) is treated as a missing/unset GPS fix
        return !(lat == 0.0 && lon == 0.0);
    }

    private static boolean isValidTime(Instant time, Instant tripStart, Instant tripEnd) {
        if (time == null || tripEnd == null) {
            return false;
        }
        if (tripStart != null && time.isBefore(tripStart)) {
            return false;
        }
        return !time.isAfter(tripEnd);
    }
}

