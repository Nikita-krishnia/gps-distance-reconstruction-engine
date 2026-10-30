package com.example.gpsengine.engine;

/**
 * Identifies the exact origin and policy rule that generated an anchor point.
 */
public enum AnchorSource {
    EMPLOYEE_ACTUAL,
    EMPLOYEE_PROFILE_FALLBACK,
    EMPLOYEE_PLANNED_FALLBACK,
    NO_SHOW_ACTUAL,
    NO_SHOW_PLANNED,
    OFFICE_ACTUAL,
    OFFICE_PLANNED
}

