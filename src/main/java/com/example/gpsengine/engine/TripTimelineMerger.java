package com.example.gpsengine.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Merges cleaned GPS points and synthesized milestone anchors into a single,
 * canonical, chronologically-ordered timeline.
 *
 * DETERMINISTIC TIE-BREAK ORDER:
 * 1. Timestamp: strictly ascending chronological sequence.
 * 2. Origin: GPS points precede ANCHOR points at the exact same instant.
 * 3. Anchor Source: AnchorSource natural enum definition order.
 * 4. Geometry: Latitude ascending, then Longitude ascending.
 *
 * Time Complexity: O(N log N) worst-case (O(N) with Java's adaptive TimSort since both streams are pre-sorted),
 *                  where N = number of cleaned GPS points + anchors.
 * Space Complexity: O(N) auxiliary space for the merged timeline list.
 */
public class TripTimelineMerger {

    private static final Comparator<TripPoint> TIMELINE_COMPARATOR = Comparator
            .comparing(TripPoint::timestamp)
            .thenComparing(TripPoint::origin)
            .thenComparing(p -> p.anchorSource() != null ? p.anchorSource().ordinal() : -1)
            .thenComparingDouble(TripPoint::latitude)
            .thenComparingDouble(TripPoint::longitude);

    /**
     * Merges cleaned GPS points and anchor points into a single unmodifiable sorted list.
     *
     * @param cleanedPoints clean GPS points (remains unmutated)
     * @param anchors synthesized milestone anchor points (remains unmutated)
     * @return an immutable, chronologically ordered list of TripPoints
     */
    public List<TripPoint> merge(List<GpsPoint> cleanedPoints, List<TripPoint> anchors) {
        int capacity = (cleanedPoints != null ? cleanedPoints.size() : 0)
                + (anchors != null ? anchors.size() : 0);

        if (capacity == 0) {
            return List.of();
        }

        List<TripPoint> timeline = new ArrayList<>(capacity);

        if (cleanedPoints != null) {
            for (GpsPoint gps : cleanedPoints) {
                if (gps != null) {
                    timeline.add(TripPoint.fromGps(gps));
                }
            }
        }

        if (anchors != null) {
            for (TripPoint anchor : anchors) {
                if (anchor != null) {
                    timeline.add(anchor);
                }
            }
        }

        timeline.sort(TIMELINE_COMPARATOR);

        return Collections.unmodifiableList(timeline);
    }
}

