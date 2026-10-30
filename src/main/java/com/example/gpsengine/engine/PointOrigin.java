package com.example.gpsengine.engine;

/**
 * Distinguishes points captured directly by GPS devices from those anchored
 * by business milestones (pickup, drop, no-show, office).
 */
public enum PointOrigin {
    GPS,
    ANCHOR
}

