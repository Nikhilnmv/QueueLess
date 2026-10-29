package com.queueless.notification.domain;

public enum NotificationType {
    APPOINTMENT_CONFIRMED,
    /** An appointment was cancelled by organization staff. */
    APPOINTMENT_CANCELLED,
    /** A walk-in ticket was cancelled by organization staff. */
    TICKET_CANCELLED,
    TURN_APPROACHING,
    TURN_CALLED,
    MARKED_NO_SHOW
}
