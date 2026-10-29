package com.queueless.ticket.service;

import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import com.queueless.common.time.TimeProvider;
import com.queueless.membership.domain.Permission;
import com.queueless.membership.service.AccessControlService;
import com.queueless.membership.service.MembershipService;
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
import com.queueless.ticket.api.TicketDtos.TicketResponse;
import com.queueless.ticket.domain.QueueTicket;
import com.queueless.ticket.domain.QueueTicketRepository;
import com.queueless.ticket.domain.QueueTicketRepository.TicketOwnership;
import com.queueless.ticket.domain.TicketPriority;
import com.queueless.ticket.domain.TicketStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket creation and customer-side ticket operations. Every write locks the queue first
 * ({@link QueueLockService}), so token numbers, the one-active-ticket rules and positions are
 * always computed from a state no other transaction is changing at the same time.
 */
@Service
public class TicketService {

    private final QueueTicketRepository tickets;
    private final QueueService queues;
    private final QueueLockService locks;
    private final OrganizationService organizations;
    private final AccessControlService access;
    private final MembershipService memberships;
    private final TokenAllocator tokens;
    private final TicketTransitions transitions;
    private final QueueChangeRecorder changes;
    private final NotificationService notifications;
    private final TicketViews views;
    private final TimeProvider time;

    public TicketService(QueueTicketRepository tickets, QueueService queues, QueueLockService locks,
            OrganizationService organizations, AccessControlService access, MembershipService memberships,
            TokenAllocator tokens, TicketTransitions transitions, QueueChangeRecorder changes,
            NotificationService notifications, TicketViews views, TimeProvider time) {
        this.tickets = tickets;
        this.queues = queues;
        this.locks = locks;
        this.organizations = organizations;
        this.access = access;
        this.memberships = memberships;
        this.tokens = tokens;
        this.transitions = transitions;
        this.changes = changes;
        this.notifications = notifications;
        this.views = views;
        this.time = time;
    }

    /** A registered customer joins today's waiting line ("obtain a token"). */
    @Transactional
    public TicketResponse joinQueue(UUID queueId, UUID userId) {
        LockedQueue locked = locks.lock(queueId);
        ServiceQueue queue = locked.queue();
        Organization organization = organizations.requireActive(queue.getOrganizationId());
        requireAcceptingArrivals(queue);

        Instant now = time.now();
        LocalDate today = LocalDate.ofInstant(now, organization.zoneId());
        tickets.findActiveForUser(queueId, userId, today).ifPresent(existing -> {
            throw alreadyInQueue(queue, existing);
        });
        int token = tokens.nextToken(locked, today);
        QueueTicket ticket = tickets.saveAndFlush(QueueTicket.walkIn(queueId, userId, today, token, now));
        memberships.ensureCustomerMembership(organization.getId(), userId);
        changes.record(locked, today, QueueChangeReason.TICKET_CREATED);
        return views.describe(ticket, queue, organization);
    }

    /** Staff register a walk-in for a person without an account; staff may set the priority. */
    @Transactional
    public BoardTicket registerGuest(UUID queueId, UUID staffId, String guestName, TicketPriority priority) {
        UUID organizationId = queues.requireOrganizationId(queueId);
        access.require(staffId, organizationId, Permission.REGISTER_GUEST_WALK_IN);
        LockedQueue locked = locks.lock(queueId);
        ServiceQueue queue = locked.queue();
        Organization organization = organizations.requireActive(organizationId);
        requireAcceptingArrivals(queue);

        Instant now = time.now();
        LocalDate today = LocalDate.ofInstant(now, organization.zoneId());
        int token = tokens.nextToken(locked, today);
        QueueTicket ticket = tickets.saveAndFlush(QueueTicket.guestWalkIn(queueId, guestName.trim(),
                priority == null ? TicketPriority.NORMAL : priority, today, token, now));
        changes.record(locked, today, QueueChangeReason.TICKET_CREATED);
        return boardTicket(ticket.getId(), queue);
    }

    /** Reserves the token of a newly booked appointment. Called by the booking transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public QueueTicket reserveForAppointment(LockedQueue locked, UUID appointmentId, UUID userId,
            LocalDate serviceDate) {
        tickets.findActiveForUser(locked.id(), userId, serviceDate).ifPresent(existing -> {
            throw alreadyInQueue(locked.queue(), existing);
        });
        int token = tokens.nextToken(locked, serviceDate);
        return tickets.saveAndFlush(
                QueueTicket.forAppointment(locked.id(), userId, appointmentId, serviceDate, token, time.now()));
    }

    /** BOOKED -> WAITING; the ticket's place in line is decided by this check-in time. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void checkIn(LockedQueue locked, QueueTicket ticket, UUID actorId) {
        TicketStatus previous = ticket.getStatus();
        ticket.checkIn(time.now());
        transitions.changed(ticket, previous, actorId);
        changes.record(locked, ticket.getServiceDate(), QueueChangeReason.TICKET_CHECKED_IN);
    }

    /** Owner ("leave the queue") or staff cancels a ticket. Repeating the request is harmless. */
    @Transactional
    public TicketResponse cancel(UUID ticketId, UUID actorId) {
        TicketOwnership ownership = tickets.findOwnership(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        boolean owner = actorId.equals(ownership.userId());
        if (!owner && !access.has(actorId, ownership.organizationId(), Permission.MANAGE_CUSTOMER_BOOKINGS)) {
            throw ApiException.notFound("Ticket"); // do not reveal other customers' tickets
        }
        LockedQueue locked = locks.lock(ownership.queueId());
        Organization organization = organizations.requireActive(ownership.organizationId());
        QueueTicket ticket = tickets.findById(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        cancelLocked(locked, organization, ticket, actorId, QueueChangeReason.TICKET_CANCELLED);
        return views.describe(ticket, locked.queue(), organization);
    }

    /**
     * Cancels a BOOKED or WAITING ticket under the queue lock (also used for appointment
     * cancellation). Returns false if it was already cancelled (idempotent).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelLocked(LockedQueue locked, Organization organization, QueueTicket ticket, UUID actorId,
            QueueChangeReason reason) {
        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            return false;
        }
        if (!ticket.getStatus().canTransitionTo(TicketStatus.CANCELLED)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This ticket is already at the counter or finished and can no longer be cancelled")
                    .with("currentStatus", ticket.getStatus().name());
        }
        TicketStatus previous = ticket.getStatus();
        ticket.cancel(time.now());
        transitions.changed(ticket, previous, actorId);
        if (ticket.getUserId() != null && !ticket.getUserId().equals(actorId)) {
            ServiceQueue queue = locked.queue();
            boolean appointment = ticket.getAppointmentId() != null;
            notifications.notify(ticket.getUserId(), ticket.getId(),
                    appointment ? NotificationType.APPOINTMENT_CANCELLED : NotificationType.TICKET_CANCELLED,
                    appointment ? "Appointment cancelled" : "Ticket cancelled",
                    "Token " + queue.displayToken(ticket.getTokenNumber()) + " for " + queue.getName()
                            + " was cancelled by " + organization.getName() + ".");
        }
        changes.record(locked, ticket.getServiceDate(), reason);
        return true;
    }

    BoardTicket boardTicket(UUID ticketId, ServiceQueue queue) {
        return tickets.findBoardRow(ticketId)
                .map(row -> views.boardTicket(row, queue, null, null))
                .orElseThrow(() -> ApiException.notFound("Ticket"));
    }

    private static void requireAcceptingArrivals(ServiceQueue queue) {
        if (!queue.acceptsNewArrivals()) {
            throw new ApiException(ErrorCode.QUEUE_NOT_ACCEPTING,
                    queue.getStatus() == QueueStatus.CLOSED
                            ? "This queue is closed right now"
                            : "This queue is not accepting customers right now");
        }
    }

    private static ApiException alreadyInQueue(ServiceQueue queue, QueueTicket existing) {
        return new ApiException(ErrorCode.ALREADY_IN_QUEUE, "You already hold token "
                + queue.displayToken(existing.getTokenNumber()) + " in this queue for that day")
                .with("ticketId", existing.getId());
    }
}
