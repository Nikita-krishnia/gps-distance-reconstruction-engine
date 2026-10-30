package com.example.gpsengine.config;

import com.example.gpsengine.engine.SegmentConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration creating the SegmentConfig bean from application properties.
 *
 * Notice that max-speed-kmh reuses the single central property `gps.cleaning.max-speed-kmh`
 * so there is exactly one unified speed threshold across the system.
 */
@Configuration
public class SegmentConfiguration {

    @Bean
    public SegmentConfig segmentConfig(
            @Value("${gps.distance.short-gap-seconds:70}") int shortGapSeconds,
            @Value("${gps.distance.parked-speed-kmh:5.0}") double parkedSpeedKmh,
            @Value("${gps.cleaning.max-speed-kmh:120.0}") double maxSpeedKmh,
            @Value("${gps.distance.max-route-meters:100000.0}") double maxRouteMeters,
            @Value("${gps.distance.fallback-multiplier:1.4}") double fallbackMultiplier
    ) {
        return new SegmentConfig(
                shortGapSeconds,
                parkedSpeedKmh,
                maxSpeedKmh,
                maxRouteMeters,
                fallbackMultiplier
        );
    }
}

