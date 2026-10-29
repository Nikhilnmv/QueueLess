package com.queueless.ticket.domain;

/**
 * Staff-controlled priority. Lower rank is served first. The ranks must match the
 * {@code priority_rank} generated column in V2__queues_appointments_tickets.sql.
 */
public enum TicketPriority {
    HIGH(1),
    NORMAL(2),
    LOW(3);

    private final int rank;

    TicketPriority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
