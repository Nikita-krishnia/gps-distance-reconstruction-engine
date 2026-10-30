package com.example.gpsengine.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Logs a privacy-safe startup configuration summary.
 * NEVER logs secrets, passwords, tokens, URLs, or personal data.
 */
@Component
public class StartupSummaryLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSummaryLogger.class);

    private final String providerName;
    private final boolean cacheEnabled;
    private final long cacheTtlHours;
    private final double cellSizeDegrees;
    private final int breakerThreshold;
    private final long breakerOpenSeconds;
    private final boolean recomputeJobEnabled;
    private final boolean seedingEnabled;
    private final String structuredLoggingFormat;

    public StartupSummaryLogger(
            @Value("${routing.provider:fake}") String providerName,
            @Value("${routing.cache.enabled:true}") boolean cacheEnabled,
            @Value("${routing.cache.ttl-hours:24}") long cacheTtlHours,
            @Value("${routing.cache.cell-size-degrees:0.001}") double cellSizeDegrees,
            @Value("${routing.breaker.failure-threshold:5}") int breakerThreshold,
            @Value("${routing.breaker.open-seconds:30}") long breakerOpenSeconds,
            @Value("${recompute.enabled:true}") boolean recomputeJobEnabled,
            @Value("${auth.seed.enabled:true}") boolean seedingEnabled,
            @Value("${logging.structured.format.console:none}") String structuredLoggingFormat) {
        this.providerName = providerName;
        this.cacheEnabled = cacheEnabled;
        this.cacheTtlHours = cacheTtlHours;
        this.cellSizeDegrees = cellSizeDegrees;
        this.breakerThreshold = breakerThreshold;
        this.breakerOpenSeconds = breakerOpenSeconds;
        this.recomputeJobEnabled = recomputeJobEnabled;
        this.seedingEnabled = seedingEnabled;
        this.structuredLoggingFormat = structuredLoggingFormat;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean structuredLoggingOn = !"none".equalsIgnoreCase(structuredLoggingFormat) && !structuredLoggingFormat.isBlank();
        log.info("Startup configuration: provider={}, cache={}, cacheTtlHours={}, cellSizeDegrees={}, breakerThreshold={}, breakerOpenSeconds={}, recomputeJob={}, seeding={}, structuredLogging={}",
                providerName, cacheEnabled, cacheTtlHours, cellSizeDegrees,
                breakerThreshold, breakerOpenSeconds, recomputeJobEnabled,
                seedingEnabled, structuredLoggingOn);
    }
}

