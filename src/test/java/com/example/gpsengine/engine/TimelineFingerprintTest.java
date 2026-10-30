package com.example.gpsengine.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineFingerprintTest {

    private final String configFingerprint = "clean(acc=50.0,spd=120.0)|anchor(rad=400.0,noshow=false)|seg(gap=70,park=5.0,maxspd=120.0,maxm=100000.0,mul=1.40)|route(prov=fake,cache=true,cell=0.001000,cver=v1)";
    private final String ruleVersion = "2026.1";

    @Test
    @DisplayName("Fingerprint is exactly 64 hexadecimal characters and deterministic for identical timelines")
    void deterministic64HexChars() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = new TripPoint(12.9716, 77.5946, t0, PointOrigin.GPS, null);
        TripPoint p2 = new TripPoint(12.9730, 77.5960, t0.plusSeconds(30), PointOrigin.GPS, null);
        List<TripPoint> timeline = List.of(p1, p2);

        String hash1 = TimelineFingerprint.compute(timeline, configFingerprint, ruleVersion);
        String hash2 = TimelineFingerprint.compute(timeline, configFingerprint, ruleVersion);

        assertThat(hash1).hasSize(64).matches("^[0-9a-f]{64}$");
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("Changing one coordinate by 0.000001 degrees changes the hash")
    void coordinateChangeChangesHash() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = new TripPoint(12.9716, 77.5946, t0, PointOrigin.GPS, null);
        TripPoint p2 = new TripPoint(12.9730, 77.5960, t0.plusSeconds(30), PointOrigin.GPS, null);
        List<TripPoint> timeline1 = List.of(p1, p2);

        TripPoint p2Shifted = new TripPoint(12.973001, 77.5960, t0.plusSeconds(30), PointOrigin.GPS, null);
        List<TripPoint> timeline2 = List.of(p1, p2Shifted);

        String hash1 = TimelineFingerprint.compute(timeline1, configFingerprint, ruleVersion);
        String hash2 = TimelineFingerprint.compute(timeline2, configFingerprint, ruleVersion);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("Changing rule version, config fingerprint, or timestamp nanoseconds changes the hash")
    void metadataChangesChangeHash() {
        Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
        TripPoint p1 = new TripPoint(12.9716, 77.5946, t0, PointOrigin.GPS, null);
        TripPoint p2 = new TripPoint(12.9730, 77.5960, t0.plusSeconds(30), PointOrigin.GPS, null);
        List<TripPoint> timeline = List.of(p1, p2);

        String baseHash = TimelineFingerprint.compute(timeline, configFingerprint, ruleVersion);

        String hashNewVersion = TimelineFingerprint.compute(timeline, configFingerprint, "2026.2");
        assertThat(hashNewVersion).isNotEqualTo(baseHash);

        String hashNewConfig = TimelineFingerprint.compute(timeline, configFingerprint + "|diff", ruleVersion);
        assertThat(hashNewConfig).isNotEqualTo(baseHash);

        TripPoint p2Nanos = new TripPoint(12.9730, 77.5960, t0.plusSeconds(30).plusNanos(5), PointOrigin.GPS, null);
        String hashNewNanos = TimelineFingerprint.compute(List.of(p1, p2Nanos), configFingerprint, ruleVersion);
        assertThat(hashNewNanos).isNotEqualTo(baseHash);
    }
}

