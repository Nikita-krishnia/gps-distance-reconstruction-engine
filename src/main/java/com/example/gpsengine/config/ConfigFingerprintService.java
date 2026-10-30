package com.example.gpsengine.config;

import com.example.gpsengine.engine.AnchorConfig;
import com.example.gpsengine.engine.GpsCleaningConfig;
import com.example.gpsengine.engine.SegmentConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Builds a canonical fingerprint string capturing all active configuration thresholds
 * that directly impact distance reconstruction outcomes.
 *
 * <p>Changing any of these configuration properties automatically alters the config fingerprint,
 * thereby producing a distinct inputHash and triggering recomputation instead of stale cache reuse.
 */
@Component
public class ConfigFingerprintService {

    private final String fingerprint;

    public ConfigFingerprintService(
            GpsCleaningConfig cleaningConfig,
            AnchorConfig anchorConfig,
            SegmentConfig segmentConfig) {
        this(cleaningConfig, anchorConfig, segmentConfig, "fake", true, 0.001, "v1");
    }

    @Autowired
    public ConfigFingerprintService(
            GpsCleaningConfig cleaningConfig,
            AnchorConfig anchorConfig,
            SegmentConfig segmentConfig,
            @Value("${routing.provider:fake}") String routingProvider,
            @Value("${routing.cache.enabled:true}") boolean cacheEnabled,
            @Value("${routing.cache.cell-size-degrees:0.001}") double cellSizeDegrees,
            @Value("${routing.cache.provider-version:v1}") String cacheProviderVersion) {

        this.fingerprint = String.format(Locale.ROOT,
                "clean(acc=%.1f,spd=%.1f)|anchor(rad=%.1f,noshow=%b)|seg(gap=%d,park=%.1f,maxspd=%.1f,maxm=%.1f,mul=%.2f)|route(prov=%s,cache=%b,cell=%.6f,cver=%s)",
                cleaningConfig.accuracyThresholdMeters(),
                cleaningConfig.maxSpeedKmh(),
                anchorConfig.officeRadiusMeters(),
                anchorConfig.includeNoShow(),
                segmentConfig.shortGapSeconds(),
                segmentConfig.parkedSpeedKmh(),
                segmentConfig.maxSpeedKmh(),
                segmentConfig.maxRouteMeters(),
                segmentConfig.fallbackMultiplier(),
                routingProvider.trim().toLowerCase(),
                cacheEnabled,
                cellSizeDegrees,
                cacheProviderVersion.trim()
        );
    }

    public String getFingerprint() {
        return fingerprint;
    }
}

