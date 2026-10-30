package com.example.gpsengine.config;

import com.example.gpsengine.engine.GpsCleaningConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration bridge that reads externalized GPS cleaning properties
 * and registers a GpsCleaningConfig bean.
 *
 * The engine package remains strictly framework-free; this configuration lives in
 * the Spring config layer.
 */
@Configuration
public class GpsCleaningConfiguration {

    @Bean
    public GpsCleaningConfig gpsCleaningConfig(
            @Value("${gps.cleaning.accuracy-threshold-meters:50.0}") double accuracyThresholdMeters,
            @Value("${gps.cleaning.max-speed-kmh:120.0}") double maxSpeedKmh
    ) {
        return new GpsCleaningConfig(accuracyThresholdMeters, maxSpeedKmh);
    }
}

