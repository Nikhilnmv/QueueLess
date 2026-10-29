package com.queueless.ticket.service;

import com.queueless.ticket.domain.QueueTicket;
import com.queueless.ticket.domain.TicketStatus;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Informs {@link TicketLifecycleListener}s (e.g. appointment sync) after a ticket status change. */
@Component
public class TicketTransitions {

    private final ObjectProvider<TicketLifecycleListener> listeners;

    public TicketTransitions(ObjectProvider<TicketLifecycleListener> listeners) {
        this.listeners = listeners;
    }

    public void changed(QueueTicket ticket, TicketStatus previousStatus, UUID actorUserId) {
        if (ticket.getStatus() != previousStatus) {
            listeners.orderedStream().forEach(listener ->
                    listener.onTicketStatusChanged(ticket, previousStatus, actorUserId));
        }
    }
}
