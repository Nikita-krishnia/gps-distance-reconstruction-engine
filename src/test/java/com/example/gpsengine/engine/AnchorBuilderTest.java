package com.example.gpsengine.engine;

import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.RiderKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class AnchorBuilderTest {

    private final AnchorBuilder builder = new AnchorBuilder();
    private final AnchorConfig defaultConfig = AnchorConfig.defaults();

    private final Instant tripStart = Instant.parse("2026-10-30T08:00:00Z");
    private final Instant tripEnd = Instant.parse("2026-10-30T09:00:00Z");

    private final double plannedOfficeLat = 12.9716;
    private final double plannedOfficeLon = 77.5946;

    @Test
    @DisplayName("Actual pickup and drop milestones with valid coordinates and time should create EMPLOYEE_ACTUAL anchors")
    void actualPickupAndDropValidUsed() {
        MilestoneInput pickup = new MilestoneInput(
                MilestoneType.PICKUP, "emp-1", RiderKind.PLANNED,
                12.9100, 77.5800, tripStart.plusSeconds(600),
                12.9105, 77.5805, tripStart.plusSeconds(600),
                null, null, null
        );

        MilestoneInput drop = new MilestoneInput(
                MilestoneType.DROP, "emp-2", RiderKind.PLANNED,
                12.9500, 77.5900, tripStart.plusSeconds(1800),
                12.9505, 77.5905, tripStart.plusSeconds(1800),
                null, null, null
        );

        AnchorResult result = builder.buildAnchors(
                List.of(pickup, drop),
                tripStart, tripEnd,
                plannedOfficeLat, plannedOfficeLon,
                plannedOfficeLat, plannedOfficeLon,
                defaultConfig
        );

        assertThat(result.actualUsed()).isEqualTo(2);
        assertThat(result.profileFallbackUsed()).isEqualTo(0);
        assertThat(result.plannedFallbackUsed()).isEqualTo(0);
        assertThat(result.anchors()).hasSize(3); // 2 employee + 1 office

        TripPoint pickupAnchor = result.anchors().get(0);
        assertThat(pickupAnchor.anchorSource()).isEqualTo(AnchorSource.EMPLOYEE_ACTUAL);
        assertThat(pickupAnchor.latitude()).isEqualTo(12.9100);

        TripPoint dropAnchor = result.anchors().get(1);
        assertThat(dropAnchor.anchorSource()).isEqualTo(AnchorSource.EMPLOYEE_ACTUAL);
        assertThat(dropAnchor.latitude()).isEqualTo(12.9500);
    }

    @Test
    @DisplayName("Actual location invalid (null, out of range, or (0,0)) falls back to planned or profile")
    void actualLocationInvalidFallsBack() {
        // lat/lon (0,0) treated as missing fix -> fall back to planned
        MilestoneInput zeroCoord = new MilestoneInput(
                MilestoneType.PICKUP, "emp-planned", RiderKind.PLANNED,
                0.0, 0.0, tripStart.plusSeconds(600),
                12.9200, 77.5850, tripStart.plusSeconds(600),
                null, null, null
        );

        // lat > 90 out of range -> fall back to profile
        MilestoneInput outOfRange = new MilestoneInput(
                MilestoneType.PICKUP, "emp-adhoc", RiderKind.AD_HOC,
                95.0, 77.5800, tripStart.plusSeconds(900),
                null, null, null,
                12.9300, 77.5870, tripStart.plusSeconds(900)
        );

        AnchorResult result = builder.buildAnchors(
                List.of(zeroCoord, outOfRange),
                tripStart, tripEnd,
                null, null,
                plannedOfficeLat, plannedOfficeLon,
                defaultConfig
        );

        assertThat(result.actualUsed()).isEqualTo(0);
        assertThat(result.plannedFallbackUsed()).isEqualTo(1);
        assertThat(result.profileFallbackUsed()).isEqualTo(1);
        assertThat(result.skippedNoValidLocation()).isEqualTo(0);

        assertThat(result.anchors().get(0).anchorSource()).isEqualTo(AnchorSource.EMPLOYEE_PLANNED_FALLBACK);
        assertThat(result.anchors().get(0).latitude()).isEqualTo(12.9200);

        assertThat(result.anchors().get(1).anchorSource()).isEqualTo(AnchorSource.EMPLOYEE_PROFILE_FALLBACK);
        assertThat(result.anchors().get(1).latitude()).isEqualTo(12.9300);
    }

    @Test
    @DisplayName("Actual time outside trip window [tripStart, tripEnd] should trigger fallback")
    void actualTimeOutsideWindowFallsBack() {
        // Actual time before tripStart
        MilestoneInput beforeStart = new MilestoneInput(
                MilestoneType.DROP, "emp-early", RiderKind.PLANNED,
                12.9400, 77.5880, tripStart.minusSeconds(120),
                12.9410, 77.5890, tripStart.plusSeconds(300),
                null, null, null
        );

        // Actual time after tripEnd
        MilestoneInput afterEnd = new MilestoneInput(
                MilestoneType.DROP, "emp-late", RiderKind.AD_HOC,
                12.9450, 77.5895, tripEnd.plusSeconds(60),
                null, null, null,
                12.9460, 77.5896, tripEnd.minusSeconds(300)
        );

        AnchorResult result = builder.buildAnchors(
                List.of(beforeStart, afterEnd),
                tripStart, tripEnd,
                null, null,
                plannedOfficeLat, plannedOfficeLon,
                defaultConfig
        );

        assertThat(result.actualUsed()).isEqualTo(0);
        assertThat(result.plannedFallbackUsed()).isEqualTo(1);
        assertThat(result.profileFallbackUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Ad-hoc rider falls back to profile and NOT planned; planned rider falls back to planned and NOT profile")
    void strictRiderKindFallbackIsolation() {
        // Ad-hoc with invalid actual, valid planned, but missing profile -> must be skipped!
        MilestoneInput adHocWithPlannedOnly = new MilestoneInput(
                MilestoneType.PICKUP, "adhoc-1", RiderKind.AD_HOC,
                null, null, null,
                12.9100, 77.5800, tripStart.plusSeconds(300), // Planned exists, but should NOT be used for ad-hoc!
                null, null, null // Profile missing
        );

        // Planned rider with invalid actual, valid profile, but missing planned -> must be skipped!
        MilestoneInput plannedWithProfileOnly = new MilestoneInput(
                MilestoneType.DROP, "planned-1", RiderKind.PLANNED,
                null, null, null,
                null, null, null, // Planned missing
                12.9200, 77.5850, tripStart.plusSeconds(600) // Profile exists, but should NOT be used for planned!
        );

        AnchorResult result = builder.buildAnchors(
                List.of(adHocWithPlannedOnly, plannedWithProfileOnly),
                tripStart, tripEnd,
                null, null,
                plannedOfficeLat, plannedOfficeLon,
                defaultConfig
        );

        assertThat(result.actualUsed()).isEqualTo(0);
        assertThat(result.plannedFallbackUsed()).isEqualTo(0);
        assertThat(result.profileFallbackUsed()).isEqualTo(0);
        assertThat(result.skippedNoValidLocation()).isEqualTo(2);
        // Only the office anchor is generated
        assertThat(result.anchors()).hasSize(1);
        assertThat(result.anchors().get(0).anchorSource()).isEqualTo(AnchorSource.OFFICE_PLANNED);
    }

    @Test
    @DisplayName("Milestone with no valid location or time across all options is skipped without exception")
    void nothingValidSkippedSafely() {
        MilestoneInput corrupted = new MilestoneInput(
                MilestoneType.PICKUP, "emp-none", RiderKind.PLANNED,
                null, null, null,
                null, null, null,
                null, null, null
        );

        AnchorResult result = builder.buildAnchors(
                List.of(corrupted),
                tripStart, tripEnd,
                null, null,
                plannedOfficeLat, plannedOfficeLon,
                defaultConfig
        );

        assertThat(result.skippedNoValidLocation()).isEqualTo(1);
        assertThat(result.actualUsed()).isEqualTo(0);
        assertThat(result.anchors()).hasSize(1); // Office anchor only
    }

    @Test
    @DisplayName("No-show milestone rules: flag off creates no anchor; flag on uses actual, then planned, then skips")
    void noShowHandlingRules() {
        MilestoneInput noShowWithActual = new MilestoneInput(
                MilestoneType.NO_SHOW, "emp-ns1", RiderKind.PLANNED,
                12.9150, 77.5820, tripStart.plusSeconds(400),
                12.9155, 77.5825, tripStart.plusSeconds(400),
                null, null, null
        );

        MilestoneInput noShowWithPlannedOnly = new MilestoneInput(
                MilestoneType.NO_SHOW, "emp-ns2", RiderKind.PLANNED,
                null, null, null,
                12.9250, 77.5840, tripStart.plusSeconds(500),
                null, null, null
        );

        MilestoneInput noShowCorrupted = new MilestoneInput(
                MilestoneType.NO_SHOW, "emp-ns3", RiderKind.PLANNED,
                null, null, null,
                null, null, null,
                null, null, null
        );

        // Case 1: Flag OFF (default) -> No-shows ignored completely
        AnchorConfig flagOffConfig = new AnchorConfig(400.0, false);
        AnchorResult resultFlagOff = builder.buildAnchors(
                List.of(noShowWithActual, noShowWithPlannedOnly, noShowCorrupted),
                tripStart, tripEnd,
                null, null,
                plannedOfficeLat, plannedOfficeLon,
                flagOffConfig
        );
        assertThat(resultFlagOff.noShowAdded()).isEqualTo(0);
        assertThat(resultFlagOff.skippedNoValidLocation()).isEqualTo(0);

        // Case 2: Flag ON -> Process actual, then planned, skip corrupt
        AnchorConfig flagOnConfig = new AnchorConfig(400.0, true);
        AnchorResult resultFlagOn = builder.buildAnchors(
                List.of(noShowWithActual, noShowWithPlannedOnly, noShowCorrupted),
                tripStart, tripEnd,
                null, null,
                plannedOfficeLat, plannedOfficeLon,
                flagOnConfig
        );
        assertThat(resultFlagOn.noShowAdded()).isEqualTo(2);
        assertThat(resultFlagOn.skippedNoValidLocation()).isEqualTo(1);

        assertThat(resultFlagOn.anchors()).anyMatch(a -> a.anchorSource() == AnchorSource.NO_SHOW_ACTUAL);
        assertThat(resultFlagOn.anchors()).anyMatch(a -> a.anchorSource() == AnchorSource.NO_SHOW_PLANNED);
    }

    @Test
    @DisplayName("Office anchor: within radius -> OFFICE_ACTUAL; exactly at limit -> inside; outside -> OFFICE_PLANNED")
    void officeAnchorRadiusBoundaryRules() {
        // 400 metres in degrees latitude along equator: 400 / 6371000 * 180 / PI ~= 0.00359728 degrees
        double deltaLat400m = (400.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);

        // 1. Inside radius (~200 metres away)
        double latInside = plannedOfficeLat + (deltaLat400m / 2.0);
        AnchorResult inside = builder.buildAnchors(
                List.of(), tripStart, tripEnd,
                latInside, plannedOfficeLon,
                plannedOfficeLat, plannedOfficeLon,
                defaultConfig
        );
        assertThat(inside.officeActualUsed()).isTrue();
        assertThat(inside.anchors().get(0).anchorSource()).isEqualTo(AnchorSource.OFFICE_ACTUAL);
        assertThat(inside.anchors().get(0).latitude()).isEqualTo(latInside);

        // 2. At the 400m radius boundary
        // Due to floating-point representation of radians in trigonometric functions,
        // we use a point right at/under the boundary (deltaLat400m - 1e-11) so distance <= 400.0m
        double latBoundary = plannedOfficeLat + (deltaLat400m - 1e-11);
        AnchorResult boundary = builder.buildAnchors(
                List.of(), tripStart, tripEnd,
                latBoundary, plannedOfficeLon,
                plannedOfficeLat, plannedOfficeLon,
                defaultConfig
        );
        assertThat(boundary.officeActualUsed()).isTrue();
        assertThat(boundary.anchors().get(0).anchorSource()).isEqualTo(AnchorSource.OFFICE_ACTUAL);

        // 3. Clearly outside radius (~600 metres away)
        double latOutside = plannedOfficeLat + (deltaLat400m * 1.5);
        AnchorResult outside = builder.buildAnchors(
                List.of(), tripStart, tripEnd,
                latOutside, plannedOfficeLon,
                plannedOfficeLat, plannedOfficeLon,
                defaultConfig
        );
        assertThat(outside.officeActualUsed()).isFalse();
        assertThat(outside.anchors().get(0).anchorSource()).isEqualTo(AnchorSource.OFFICE_PLANNED);
        assertThat(outside.anchors().get(0).latitude()).isEqualTo(plannedOfficeLat);

        // 4. Missing actualEndLat/Lon -> falls back to OFFICE_PLANNED
        AnchorResult missing = builder.buildAnchors(
                List.of(), tripStart, tripEnd,
                null, null,
                plannedOfficeLat, plannedOfficeLon,
                defaultConfig
        );
        assertThat(missing.officeActualUsed()).isFalse();
        assertThat(missing.anchors().get(0).anchorSource()).isEqualTo(AnchorSource.OFFICE_PLANNED);

        // 5. Null tripEnd throws IllegalArgumentException
        assertThatThrownBy(() -> builder.buildAnchors(
                List.of(), tripStart, null,
                plannedOfficeLat, plannedOfficeLon,
                plannedOfficeLat, plannedOfficeLon,
                defaultConfig
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("tripEnd must not be null");
    }
}
