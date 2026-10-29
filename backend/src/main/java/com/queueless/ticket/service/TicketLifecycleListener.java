package com.queueless.ticket.service;

import com.queueless.ticket.domain.QueueTicket;
import com.queueless.ticket.domain.TicketStatus;
import java.util.UUID;

/**
 * Called synchronously, inside the same transaction, after a ticket changes status.
 *
 * <p>The appointment module implements it to keep the appointment status consistent with its
 * ticket. Defining the interface here (dependency inversion) lets the ticket module stay unaware
 * of appointments, avoiding a cyclic module dependency.
 */
public interface TicketLifecycleListener {

    void onTicketStatusChanged(QueueTicket ticket, TicketStatus previousStatus, UUID actorUserId);
}
