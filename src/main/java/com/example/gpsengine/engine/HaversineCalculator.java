package com.example.gpsengine.engine;

/**
 * Pure mathematical utility computing great-circle distance between two points on a sphere
 * using the Haversine formula with a mean Earth radius of 6,371,000 metres.
 *
 * Time Complexity: O(1)
 * Space Complexity: O(1)
 */
public final class HaversineCalculator {

    public static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private HaversineCalculator() {
        // Static utility class
    }

    /**
     * Calculates the great-circle distance in metres between two coordinate pairs.
     *
     * @param lat1 latitude of point 1 in degrees
     * @param lon1 longitude of point 1 in degrees
     * @param lat2 latitude of point 2 in degrees
     * @param lon2 longitude of point 2 in degrees
     * @return distance across the Earth's surface in metres
     */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double sinDeltaPhiHalf = Math.sin(deltaPhi / 2.0);
        double sinDeltaLambdaHalf = Math.sin(deltaLambda / 2.0);

        double a = sinDeltaPhiHalf * sinDeltaPhiHalf
                + Math.cos(phi1) * Math.cos(phi2) * sinDeltaLambdaHalf * sinDeltaLambdaHalf;

        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));

        return EARTH_RADIUS_METERS * c;
    }

    /**
     * Calculates the distance in metres between two GpsPoints.
     */
    public static double distanceMeters(GpsPoint p1, GpsPoint p2) {
        return distanceMeters(p1.latitude(), p1.longitude(), p2.latitude(), p2.longitude());
    }
}

