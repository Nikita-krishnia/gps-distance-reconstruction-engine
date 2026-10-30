package com.example.gpsengine.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Synthetic geographic world model representing an urban commuter geography of ~20 km x 20 km.
 * Places have a power-law skewed popularity distribution so arterial corridors repeat naturally across trips.
 */
public class WorldModel {

    public record Place(int id, double latitude, double longitude, String name, double popularityWeight) {}

    public static final double CENTER_LAT = 12.9300;
    public static final double CENTER_LON = 77.6200;
    // ~20 km x 20 km bounds (+/- 0.09 degrees ~ +/- 10 km)
    public static final double LAT_SPAN = 0.18;
    public static final double LON_SPAN = 0.18;

    private final List<Place> places;
    private final double[] cumulativeWeights;

    public WorldModel(int placeCount, long worldSeed) {
        if (placeCount < 10) {
            throw new IllegalArgumentException("Place count must be at least 10, got: " + placeCount);
        }

        SplittableRandom rng = new SplittableRandom(worldSeed);
        this.places = new ArrayList<>(placeCount);
        this.cumulativeWeights = new double[placeCount];

        double runningSum = 0.0;
        for (int i = 0; i < placeCount; i++) {
            // Place 0 is the central tech park / main planned office
            double lat;
            double lon;
            if (i == 0) {
                lat = CENTER_LAT;
                lon = CENTER_LON;
            } else {
                lat = (CENTER_LAT - (LAT_SPAN / 2.0)) + (rng.nextDouble() * LAT_SPAN);
                lon = (CENTER_LON - (LON_SPAN / 2.0)) + (rng.nextDouble() * LON_SPAN);
            }

            // Power-law popularity skew: Place 0 is highest, head places dominate, long tail of residential nodes
            double weight = 100.0 / Math.pow(i + 1.0, 0.75);
            runningSum += weight;
            cumulativeWeights[i] = runningSum;

            String name = (i == 0) ? "Office-Hub-Alpha" : "Place-Cluster-" + i;
            places.add(new Place(i, lat, lon, name, weight));
        }
    }

    public List<Place> getPlaces() {
        return places;
    }

    public Place getOfficeHub() {
        return places.get(0);
    }

    public Place getPlace(int index) {
        return places.get(index);
    }

    public Place samplePlace(SplittableRandom rng) {
        double totalWeight = cumulativeWeights[cumulativeWeights.length - 1];
        double r = rng.nextDouble() * totalWeight;

        // Binary search on cumulative weights
        int low = 0;
        int high = cumulativeWeights.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (cumulativeWeights[mid] >= r) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return places.get(low);
    }
}

