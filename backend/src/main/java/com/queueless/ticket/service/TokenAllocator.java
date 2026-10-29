package com.queueless.ticket.service;

import com.queueless.queue.service.LockedQueue;
import com.queueless.ticket.domain.QueueTicketRepository;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates daily token numbers (1, 2, 3, ... per queue and service date).
 *
 * <p>Why MAX + 1 is safe here: the caller holds the queue row lock (the {@link LockedQueue}
 * parameter proves it), so allocations for one queue run strictly one after another. Under READ
 * COMMITTED each statement sees all rows committed before it started, and the previous lock holder
 * committed its ticket before releasing the lock, so MAX already includes it. Without the lock two
 * transactions could read the same MAX; the unique constraint uq_tickets_token is the final
 * safeguard that would then reject the second insert instead of issuing a duplicate token.
 */
@Component
public class TokenAllocator {

    private final QueueTicketRepository tickets;

    public TokenAllocator(QueueTicketRepository tickets) {
        this.tickets = tickets;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int nextToken(LockedQueue queue, LocalDate serviceDate) {
        return tickets.findMaxTokenNumber(queue.id(), serviceDate) + 1;
    }
}
