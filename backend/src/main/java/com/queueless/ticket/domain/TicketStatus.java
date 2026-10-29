package com.queueless.ticket.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Ticket lifecycle (state machine):
 *
 * <pre>
 *   BOOKED --check-in--> WAITING --call-next--> CALLED --start--> SERVING --complete--> COMPLETED
 *     |                     |                     |
 *     +------cancel---------+--> CANCELLED        +--no-show--> NO_SHOW
 * </pre>
 * COMPLETED, NO_SHOW and CANCELLED are terminal: a cancelled ticket can never re-enter the queue.
 */
public enum TicketStatus {
    BOOKED,
    WAITING,
    CALLED,
    SERVING,
    COMPLETED,
    NO_SHOW,
    CANCELLED;

    /** Tickets that still occupy a place (or a future place) in a queue. */
    public static final Set<TicketStatus> ACTIVE =
            Collections.unmodifiableSet(EnumSet.of(BOOKED, WAITING, CALLED, SERVING));

    /** The "at the counter" position; at most one per queue and day (ux_tickets_active_per_queue_day). */
    public static final Set<TicketStatus> AT_COUNTER = Collections.unmodifiableSet(EnumSet.of(CALLED, SERVING));

    public Set<TicketStatus> allowedNextStates() {
        return switch (this) {
            case BOOKED -> EnumSet.of(WAITING, CANCELLED);
            case WAITING -> EnumSet.of(CALLED, CANCELLED);
            case CALLED -> EnumSet.of(SERVING, NO_SHOW);
            case SERVING -> EnumSet.of(COMPLETED);
            case COMPLETED, NO_SHOW, CANCELLED -> EnumSet.noneOf(TicketStatus.class);
        };
    }

    public boolean canTransitionTo(TicketStatus target) {
        return allowedNextStates().contains(target);
    }

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean isTerminal() {
        return !isActive();
    }
}
