package com.campus.parking.model;

/**
 * The kinds of vehicles the system knows how to park.
 * Kept as an enum so allocation logic can switch on it safely.
 */
public enum VehicleType {
    CAR, BIKE, BUS, EV;

    /** Parses case-insensitively and gives a friendly error for bad input. */
    public static VehicleType fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Vehicle type cannot be empty");
        }
        try {
            return VehicleType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown vehicle type: " + value
                    + " (expected CAR, BIKE, BUS or EV)");
        }
    }
}
