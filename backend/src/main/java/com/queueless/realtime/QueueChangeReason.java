package com.queueless.realtime;

/** Why a queue's live state changed. Sent to SSE subscribers; carries no customer data. */
public enum QueueChangeReason {
    TICKET_CREATED,
    TICKET_CHECKED_IN,
    TICKET_CALLED,
    SERVICE_STARTED,
    TICKET_COMPLETED,
    TICKET_NO_SHOW,
    TICKET_CANCELLED,
    PRIORITY_CHANGED,
    APPOINTMENT_BOOKED,
    APPOINTMENT_CANCELLED,
    QUEUE_STATUS_CHANGED,
    QUEUE_SETTINGS_CHANGED
}
