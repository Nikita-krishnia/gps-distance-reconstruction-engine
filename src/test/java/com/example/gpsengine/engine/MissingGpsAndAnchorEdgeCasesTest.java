package com.example.gpsengine.engine;

import com.example.gpsengine.domain.MilestoneType;
import com.example.gpsengine.domain.RiderKind;
import com.example.gpsengine.domain.TripStatus;
import com.example.gpsengine.routing.FakeRoutingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class MissingGpsAndAnchorEdgeCasesTest {

    private final GpsCleaner cleaner = new GpsCleaner();
    private final AnchorBuilder anchorBuilder = new AnchorBuilder();
    private final TripTimelineMerger timelineMerger = new TripTimelineMerger();
    private final DistanceEngine distanceEngine = new DistanceEngine();

    private final GpsCleaningConfig cleaningConfig = GpsCleaningConfig.defaults();
    private final AnchorConfig anchorConfig = AnchorConfig.defaults();
    private final SegmentConfig segmentConfig = SegmentConfig.defaults();
    private final FakeRoutingProvider fakeProvider = new FakeRoutingProvider(1.3);

    private final double plannedOfficeLat = 12.9279;
    private final double plannedOfficeLon = 77.6271;
    private final Instant startTime = Instant.parse("2026-10-30T07:00:00Z");
    private final Instant endTime = Instant.parse("2026-10-30T08:00:00Z");

    @Test
    @DisplayName("Edge Case 1: Trip with NO GPS pings but valid drops and office produces contiguous well-formed segments")
    void tripWithZeroPingsProducesValidSegmentsFromAnchors() {
        MilestoneInput drop1 = new MilestoneInput(
                MilestoneType.DROP, "emp-001", RiderKind.PLANNED,
                12.9500, 77.6000, startTime.plusSeconds(900),
                12.9500, 77.6000, startTime.plusSeconds(900),
                null, null, null
        );
        MilestoneInput drop2 = new MilestoneInput(
                MilestoneType.DROP, "emp-002", RiderKind.PLANNED,
                12.9400, 77.6150, startTime.plusSeconds(1800),
                12.9400, 77.6150, startTime.plusSeconds(1800),
                null, null, null
        );

        List<MilestoneInput> milestones = List.of(drop1, drop2);

        CleaningResult cleanResult = cleaner.clean(Collections.emptyList(), cleaningConfig);
        // AnchorBuilder creates: Drop 1 -> Drop 2 -> Office = 3 points
        AnchorResult anchorResult = anchorBuilder.buildAnchors(
                milestones, startTime, endTime,
                plannedOfficeLat, plannedOfficeLon, // actual end coords
                plannedOfficeLat, plannedOfficeLon, // planned office coords
                anchorConfig
        );

        List<TripPoint> timeline = timelineMerger.merge(cleanResult.cleanedPoints(), anchorResult.anchors());
        DistanceResult result = distanceEngine.calculate(timeline, fakeProvider, segmentConfig);

        assertThat(result.status()).isEqualTo(TripStatus.CALCULATED);
        // Drop 1 -> Drop 2 -> Office = 2 segments
        assertThat(result.segments()).hasSize(2);
        assertThat(result.totalMeters()).isGreaterThan(0);
        for (int i = 0; i < result.segments().size(); i++) {
            assertThat(result.segments().get(i).sequenceNumber()).isEqualTo(i + 1);
            assertThat(result.segments().get(i).rule()).isNotNull();
            assertThat(result.segments().get(i).reason()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Edge Case 2: Trip with single GPS ping and pickup anchor produces 2 contiguous segments")
    void tripWithSinglePingProducesTwoSegments() {
        MilestoneInput pickup = new MilestoneInput(
                MilestoneType.PICKUP, "emp-001", RiderKind.PLANNED,
                12.9700, 77.5900, startTime.plusSeconds(300),
                12.9700, 77.5900, startTime.plusSeconds(300),
                null, null, null
        );
        GpsPoint singlePing = new GpsPoint(12.9500, 77.6000, startTime.plusSeconds(1200), 10.0);

        CleaningResult cleanResult = cleaner.clean(List.of(singlePing), cleaningConfig);
        // Pickup + Office anchor = 2 anchors
        AnchorResult anchorResult = anchorBuilder.buildAnchors(
                List.of(pickup), startTime, endTime,
                null, null, // No actual end coords (planned office used)
                plannedOfficeLat, plannedOfficeLon,
                anchorConfig
        );

        // Timeline: Pickup (anchor) -> Ping (gps) -> Office (anchor) = 3 points
        List<TripPoint> timeline = timelineMerger.merge(cleanResult.cleanedPoints(), anchorResult.anchors());
        DistanceResult result = distanceEngine.calculate(timeline, fakeProvider, segmentConfig);

        assertThat(result.status()).isEqualTo(TripStatus.CALCULATED);
        // 2 segments: Pickup -> Ping, and Ping -> Office
        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).to()).isEqualTo(result.segments().get(1).from());
    }

    @Test
    @DisplayName("Edge Case 3: Office actual end at 399m is accepted as actual; at 401m falls back to planned office")
    void officeRadius399mAcceptedAnd401mFallsBack() {
        // 399 metres north of planned office
        double deltaLat399m = (399.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);
        double actualLat399 = plannedOfficeLat + deltaLat399m;

        AnchorResult result399 = anchorBuilder.buildAnchors(
                Collections.emptyList(), startTime, endTime,
                actualLat399, plannedOfficeLon, // actualEnd
                plannedOfficeLat, plannedOfficeLon, // plannedOffice
                anchorConfig
        );
        TripPoint endPoint399 = result399.anchors().get(result399.anchors().size() - 1);
        assertThat(endPoint399.anchorSource()).isEqualTo(AnchorSource.OFFICE_ACTUAL);
        assertThat(endPoint399.latitude()).isEqualTo(actualLat399);

        // 400.5 metres north of planned office (strictly outside 400m boundary, catches 401m sabotage)
        double deltaLat400_5m = (400.5 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);
        double actualLat400_5 = plannedOfficeLat + deltaLat400_5m;

        AnchorResult result400_5 = anchorBuilder.buildAnchors(
                Collections.emptyList(), startTime, endTime,
                actualLat400_5, plannedOfficeLon, // actualEnd
                plannedOfficeLat, plannedOfficeLon, // plannedOffice
                anchorConfig
        );
        TripPoint endPoint400_5 = result400_5.anchors().get(result400_5.anchors().size() - 1);
        assertThat(endPoint400_5.anchorSource()).isEqualTo(AnchorSource.OFFICE_PLANNED);
        assertThat(endPoint400_5.latitude()).isEqualTo(plannedOfficeLat);

        // 401 metres north of planned office
        double deltaLat401m = (401.0 / HaversineCalculator.EARTH_RADIUS_METERS) * (180.0 / Math.PI);
        double actualLat401 = plannedOfficeLat + deltaLat401m;

        AnchorResult result401 = anchorBuilder.buildAnchors(
                Collections.emptyList(), startTime, endTime,
                actualLat401, plannedOfficeLon, // actualEnd
                plannedOfficeLat, plannedOfficeLon, // plannedOffice
                anchorConfig
        );
        TripPoint endPoint401 = result401.anchors().get(result401.anchors().size() - 1);
        assertThat(endPoint401.anchorSource()).isEqualTo(AnchorSource.OFFICE_PLANNED);
        assertThat(endPoint401.latitude()).isEqualTo(plannedOfficeLat);
        assertThat(endPoint401.longitude()).isEqualTo(plannedOfficeLon);
    }

    @Test
    @DisplayName("Edge Case 4: Invalid actual drop (0,0) falls back to profile for ad-hoc rider and planned for planned rider")
    void invalidDropCoordinatesFallbackPolicy() {
        // Ad-hoc rider with (0,0) actual coordinates falls back to profile home location
        MilestoneInput adHoc = new MilestoneInput(
                MilestoneType.DROP, "emp-adhoc", RiderKind.AD_HOC,
                0.0, 0.0, startTime.plusSeconds(600),
                null, null, null,
                12.9350, 77.6100, startTime.plusSeconds(600)
        );

        // Planned rider with actual drop outside window (before trip start) falls back to planned stop
        MilestoneInput planned = new MilestoneInput(
                MilestoneType.DROP, "emp-plan", RiderKind.PLANNED,
                12.9400, 77.6150, startTime.minusSeconds(300), // outside window!
                12.9450, 77.6200, startTime.plusSeconds(900),
                null, null, null
        );

        AnchorResult result = anchorBuilder.buildAnchors(
                List.of(adHoc, planned), startTime, endTime,
                plannedOfficeLat, plannedOfficeLon,
                plannedOfficeLat, plannedOfficeLon,
                anchorConfig
        );

        // AdHoc anchor, planned anchor, Office End = 3 anchors
        assertThat(result.anchors()).hasSize(3);

        TripPoint adHocPoint = result.anchors().stream()
                .filter(a -> a.anchorSource() == AnchorSource.EMPLOYEE_PROFILE_FALLBACK)
                .findFirst().orElseThrow();
        assertThat(adHocPoint.latitude()).isEqualTo(12.9350);

        TripPoint plannedPoint = result.anchors().stream()
                .filter(a -> a.anchorSource() == AnchorSource.EMPLOYEE_PLANNED_FALLBACK)
                .findFirst().orElseThrow();
        assertThat(plannedPoint.latitude()).isEqualTo(12.9450);
    }

    @Test
    @DisplayName("Edge Case 5: No-show flag on vs off, and no-show with invalid location uses planned stop")
    void noShowConfigAndInvalidLocationFallback() {
        MilestoneInput noShowValid = new MilestoneInput(
                MilestoneType.NO_SHOW, "emp-ns-1", RiderKind.PLANNED,
                12.9400, 77.6100, startTime.plusSeconds(600),
                12.9450, 77.6150, startTime.plusSeconds(600),
                null, null, null
        );
        MilestoneInput noShowInvalid = new MilestoneInput(
                MilestoneType.NO_SHOW, "emp-ns-2", RiderKind.PLANNED,
                0.0, 0.0, startTime.plusSeconds(900), // invalid coords
                12.9480, 77.6180, startTime.plusSeconds(900),
                null, null, null
        );

        // Config A: includeNoShow = false (default)
        AnchorConfig configOff = new AnchorConfig(400, false);
        AnchorResult resultOff = anchorBuilder.buildAnchors(
                List.of(noShowValid, noShowInvalid), startTime, endTime,
                plannedOfficeLat, plannedOfficeLon,
                plannedOfficeLat, plannedOfficeLon,
                configOff
        );
        // Only office anchor should be present
        assertThat(resultOff.anchors()).hasSize(1);

        // Config B: includeNoShow = true
        AnchorConfig configOn = new AnchorConfig(400, true);
        AnchorResult resultOn = anchorBuilder.buildAnchors(
                List.of(noShowValid, noShowInvalid), startTime, endTime,
                plannedOfficeLat, plannedOfficeLon,
                plannedOfficeLat, plannedOfficeLon,
                configOn
        );
        // Valid noShow, invalid noShow (fallen back to planned), and office end = 3 anchors
        assertThat(resultOn.anchors()).hasSize(3);

        TripPoint ns1 = resultOn.anchors().stream()
                .filter(a -> a.anchorSource() == AnchorSource.NO_SHOW_ACTUAL)
                .findFirst().orElseThrow();
        assertThat(ns1.latitude()).isEqualTo(12.9400);

        TripPoint ns2 = resultOn.anchors().stream()
                .filter(a -> a.anchorSource() == AnchorSource.NO_SHOW_PLANNED)
                .findFirst().orElseThrow();
        assertThat(ns2.latitude()).isEqualTo(12.9480);
    }
}
