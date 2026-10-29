package com.queueless.ticket.service;

import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import com.queueless.common.time.TimeProvider;
import com.queueless.membership.domain.Permission;
import com.queueless.membership.service.AccessControlService;
import com.queueless.notification.domain.NotificationType;
import com.queueless.notification.service.NotificationService;
import com.queueless.organization.domain.Organization;
import com.queueless.organization.service.OrganizationService;
import com.queueless.queue.domain.QueueStatus;
import com.queueless.queue.domain.ServiceQueue;
import com.queueless.queue.service.LockedQueue;
import com.queueless.queue.service.QueueLockService;
import com.queueless.queue.service.QueueService;
import com.queueless.realtime.QueueChangeReason;
import com.queueless.ticket.api.TicketDtos.BoardTicket;
import com.queueless.ticket.domain.QueueTicket;
import com.queueless.ticket.domain.QueueTicketRepository;
import com.queueless.ticket.domain.QueueTicketRepository.TicketOwnership;
import com.queueless.ticket.domain.TicketPriority;
import com.queueless.ticket.domain.TicketStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff operations on the live queue. Each one runs in a transaction that first takes the queue
 * row lock, so concurrent clicks by several staff members are applied one after another and can
 * never assign the same ticket twice.
 */
@Service
public class QueueOperationsService {

    private final QueueTicketRepository tickets;
    private final QueueService queues;
    private final QueueLockService locks;
    private final OrganizationService organizations;
    private final AccessControlService access;
    private final TicketTransitions transitions;
    private final QueueChangeRecorder changes;
    private final NotificationService notifications;
    private final TicketViews views;
    private final TimeProvider time;

    public QueueOperationsService(QueueTicketRepository tickets, QueueService queues, QueueLockService locks,
            OrganizationService organizations, AccessControlService access, TicketTransitions transitions,
            QueueChangeRecorder changes, NotificationService notifications, TicketViews views, TimeProvider time) {
        this.tickets = tickets;
        this.queues = queues;
        this.locks = locks;
        this.organizations = organizations;
        this.access = access;
        this.transitions = transitions;
        this.changes = changes;
        this.notifications = notifications;
        this.views = views;
        this.time = time;
    }

    /** WAITING -> CALLED for the first ticket in service order. */
    @Transactional
    public BoardTicket callNext(UUID queueId, UUID staffId) {
        UUID organizationId = queues.requireOrganizationId(queueId);
        access.require(staffId, organizationId, Permission.OPERATE_QUEUE);
        LockedQueue locked = locks.lock(queueId);
        ServiceQueue queue = locked.queue();
        Organization organization = organizations.requireActive(organizationId);
        if (!queue.canCallNext()) {
            throw new ApiException(ErrorCode.QUEUE_NOT_OPEN, queue.getStatus() == QueueStatus.PAUSED
                    ? "The queue is paused. Resume it to call the next ticket."
                    : "The queue is closed. Open it to call the next ticket.");
        }
        Instant now = time.now();
        LocalDate today = LocalDate.ofInstant(now, organization.zoneId());
        tickets.findAtCounter(queueId, today).ifPresent(current -> {
            throw new ApiException(ErrorCode.ACTIVE_TICKET_EXISTS, "Token "
                    + queue.displayToken(current.getTokenNumber())
                    + " is still at the counter. Complete it or mark it as a no-show first.")
                    .with("ticketId", current.getId());
        });
        QueueTicket next = tickets.findWaitingInServiceOrder(queueId, today, Limit.of(1)).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NO_WAITING_TICKETS, "Nobody is waiting in this queue"));

        TicketStatus previous = next.getStatus();
        next.call(now);
        // Flush inside the lock: ux_tickets_active_per_queue_day is verified before we notify anyone.
        tickets.flush();
        transitions.changed(next, previous, staffId);
        if (next.getUserId() != null) {
            notifications.notify(next.getUserId(), next.getId(), NotificationType.TURN_CALLED, "It's your turn",
                    "Token " + queue.displayToken(next.getTokenNumber()) + " - please go to " + queue.getName()
                            + " now.");
        }
        changes.record(locked, today, QueueChangeReason.TICKET_CALLED);
        return boardTicket(next.getId(), queue);
    }

    /** CALLED -> SERVING. */
    @Transactional
    public BoardTicket startService(UUID ticketId, UUID staffId) {
        StaffTicket target = lockForStaff(ticketId, staffId, Permission.OPERATE_QUEUE);
        QueueTicket ticket = target.ticket();
        TicketStatus previous = ticket.getStatus();
        ticket.startService(time.now());
        transitions.changed(ticket, previous, staffId);
        changes.record(target.locked(), ticket.getServiceDate(), QueueChangeReason.SERVICE_STARTED);
        return boardTicket(ticketId, target.locked().queue());
    }

    /** SERVING -> COMPLETED. The service duration feeds future wait estimates. */
    @Transactional
    public BoardTicket complete(UUID ticketId, UUID staffId) {
        StaffTicket target = lockForStaff(ticketId, staffId, Permission.OPERATE_QUEUE);
        QueueTicket ticket = target.ticket();
        TicketStatus previous = ticket.getStatus();
        ticket.complete(time.now());
        transitions.changed(ticket, previous, staffId);
        changes.record(target.locked(), ticket.getServiceDate(), QueueChangeReason.TICKET_COMPLETED);
        return boardTicket(ticketId, target.locked().queue());
    }

    /** CALLED -> NO_SHOW, freeing the counter for the next ticket. */
    @Transactional
    public BoardTicket markNoShow(UUID ticketId, UUID staffId) {
        StaffTicket target = lockForStaff(ticketId, staffId, Permission.OPERATE_QUEUE);
        QueueTicket ticket = target.ticket();
        ServiceQueue queue = target.locked().queue();
        TicketStatus previous = ticket.getStatus();
        ticket.markNoShow(time.now());
        transitions.changed(ticket, previous, staffId);
        if (ticket.getUserId() != null) {
            notifications.notify(ticket.getUserId(), ticket.getId(), NotificationType.MARKED_NO_SHOW,
                    "Marked as no-show", "Token " + queue.displayToken(ticket.getTokenNumber())
                            + " was called but not answered, so it was closed. Please join the queue again if needed.");
        }
        changes.record(target.locked(), ticket.getServiceDate(), QueueChangeReason.TICKET_NO_SHOW);
        return boardTicket(ticketId, queue);
    }

    /** Re-prioritise a BOOKED or WAITING ticket. Only staff can do this; customers never set priority. */
    @Transactional
    public BoardTicket changePriority(UUID ticketId, UUID staffId, TicketPriority priority) {
        StaffTicket target = lockForStaff(ticketId, staffId, Permission.CHANGE_TICKET_PRIORITY);
        QueueTicket ticket = target.ticket();
        if (ticket.getPriority() != priority) {
            ticket.changePriority(priority, time.now());
            changes.record(target.locked(), ticket.getServiceDate(), QueueChangeReason.PRIORITY_CHANGED);
        }
        return boardTicket(ticketId, target.locked().queue());
    }

    private record StaffTicket(LockedQueue locked, QueueTicket ticket) {}

    /**
     * Authorizes a staff operation on a ticket of the ticket's own organization, then locks the
     * queue and loads the ticket (first load in this transaction, so its state is current).
     * Non-members get 404 unless the ticket is their own (then 403): they cannot learn that other
     * organizations' tickets exist.
     */
    private StaffTicket lockForStaff(UUID ticketId, UUID staffId, Permission permission) {
        TicketOwnership ownership = tickets.findOwnership(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        if (!access.has(staffId, ownership.organizationId(), permission)) {
            if (staffId.equals(ownership.userId())) {
                throw ApiException.accessDenied("Only staff of this organization can " + permission.description());
            }
            throw ApiException.notFound("Ticket");
        }
        organizations.requireActive(ownership.organizationId());
        LockedQueue locked = locks.lock(ownership.queueId());
        QueueTicket ticket = tickets.findById(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        return new StaffTicket(locked, ticket);
    }

    private BoardTicket boardTicket(UUID ticketId, ServiceQueue queue) {
        return tickets.findBoardRow(ticketId)
                .map(row -> views.boardTicket(row, queue, null, null))
                .orElseThrow(() -> ApiException.notFound("Ticket"));
    }
}
