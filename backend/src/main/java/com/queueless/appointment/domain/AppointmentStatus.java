package com.queueless.appointment.domain;

/**
 * Appointment lifecycle, kept consistent with its queue ticket in the same transaction:
 * BOOKED (ticket BOOKED) -> CHECKED_IN (ticket WAITING/CALLED/SERVING) -> COMPLETED or NO_SHOW;
 * BOOKED or CHECKED_IN -> CANCELLED. Every status except CANCELLED consumes slot capacity.
 */
public enum AppointmentStatus {
    BOOKED,
    CHECKED_IN,
    COMPLETED,
    NO_SHOW,
    CANCELLED;

    public boolean isActive() {
        return this == BOOKED || this == CHECKED_IN;
    }

    public boolean consumesCapacity() {
        return this != CANCELLED;
    }
}
