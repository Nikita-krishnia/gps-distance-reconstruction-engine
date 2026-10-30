package com.example.gpsengine.config;

import com.example.gpsengine.engine.AnchorConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration registering the AnchorConfig bean from externalized properties.
 */
@Configuration
public class AnchorConfiguration {

    @Bean
    public AnchorConfig anchorConfig(
            @Value("${gps.anchors.office-radius-meters:400.0}") double officeRadiusMeters,
            @Value("${gps.anchors.include-no-show:false}") boolean includeNoShow
    ) {
        return new AnchorConfig(officeRadiusMeters, includeNoShow);
    }
}

