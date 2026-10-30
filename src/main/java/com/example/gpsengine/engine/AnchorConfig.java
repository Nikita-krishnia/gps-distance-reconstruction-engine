package com.example.gpsengine.engine;

/**
 * Configuration parameters for the milestone anchor synthesis rules.
 *
 * @param officeRadiusMeters radius in metres to accept the actual trip-end point as the office (default: 400m)
 * @param includeNoShow whether NO_SHOW milestones should generate anchor points on the timeline (default: false)
 */
public record AnchorConfig(
        double officeRadiusMeters,
        boolean includeNoShow
) {
    public static final double DEFAULT_OFFICE_RADIUS_METERS = 400.0;
    public static final boolean DEFAULT_INCLUDE_NO_SHOW = false;

    public static AnchorConfig defaults() {
        return new AnchorConfig(DEFAULT_OFFICE_RADIUS_METERS, DEFAULT_INCLUDE_NO_SHOW);
    }
}

