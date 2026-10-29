package com.queueless.ticket.domain;

public enum TicketSource {
    /** Reserved when an appointment is booked; joins the line at check-in. */
    APPOINTMENT,
    /** A registered customer joined the queue themselves. */
    WALK_IN,
    /** Registered at the desk by staff for a person without an account. */
    GUEST_WALK_IN
}
