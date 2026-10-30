package com.example.gpsengine.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Tag("unit")
class HaversineCalculatorTest {

    @Test
    @DisplayName("One degree of latitude along the equator should be approximately 111.19 km")
    void oneDegreeOfLatitudeIsApproximately111Km() {
        double distance = HaversineCalculator.distanceMeters(0.0, 0.0, 1.0, 0.0);

        // 1 degree latitude = (2 * PI * 6,371,000 m) / 360 ~= 111,194.9 metres (~111.19 km)
        assertThat(distance).isCloseTo(111_195.0, within(100.0));
    }

    @Test
    @DisplayName("Calculating distance between identical points should yield 0.0 metres")
    void samePointGivesZeroDistance() {
        double lat = 12.971598;
        double lon = 77.594562;

        double distance = HaversineCalculator.distanceMeters(lat, lon, lat, lon);

        assertThat(distance).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Distance between Bangalore and Chennai should match the known great-circle distance (~290 km)")
    void knownCityPairDistanceMatchesExpected() {
        // Bangalore Vidhana Soudha: 12.9791, 77.5913
        // Chennai Central: 13.0827, 80.2707
        double distance = HaversineCalculator.distanceMeters(12.9791, 77.5913, 13.0827, 80.2707);

        // Great-circle distance between Bangalore and Chennai is approximately 290.5 km (290,500 m)
        assertThat(distance).isCloseTo(290_500.0, within(1_500.0));
    }
}

