package com.queueless.ticket.domain;

import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * A numbered place in one queue on one service date. Status changes only through the methods
 * below, which enforce {@link TicketStatus}'s state machine; callers must hold the queue lock.
 */
@Entity
@Table(name = "queue_tickets")
public class QueueTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "queue_id", nullable = false, updatable = false)
    private UUID queueId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "guest_name", length = 120, updatable = false)
    private String guestName;

    @Column(name = "appointment_id", updatable = false)
    private UUID appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private TicketSource source;

    /** Organization-local date the ticket belongs to; tokens restart at 1 every service date. */
    @Column(name = "service_date", nullable = false, updatable = false)
    private LocalDate serviceDate;

    @Column(name = "token_number", nullable = false, updatable = false)
    private int tokenNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TicketStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private TicketPriority priority;

    /**
     * Computed by PostgreSQL from {@code priority} (GENERATED ALWAYS ... STORED). Read-only here and
     * used only in ORDER BY clauses; Java code uses {@link TicketPriority#rank()} instead.
     */
    @Column(name = "priority_rank", insertable = false, updatable = false)
    private Short priorityRank;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "called_at")
    private Instant calledAt;

    @Column(name = "service_started_at")
    private Instant serviceStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "no_show_at")
    private Instant noShowAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "approach_notified_at")
    private Instant approachNotifiedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected QueueTicket() {
    }

    private QueueTicket(UUID queueId, UUID userId, String guestName, UUID appointmentId, TicketSource source,
            LocalDate serviceDate, int tokenNumber, TicketStatus status, TicketPriority priority, Instant now) {
        this.queueId = queueId;
        this.userId = userId;
        this.guestName = guestName;
        this.appointmentId = appointmentId;
        this.source = source;
        this.serviceDate = serviceDate;
        this.tokenNumber = tokenNumber;
        this.status = status;
        this.priority = priority;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Token reserved at booking time; the customer joins the waiting line at check-in. */
    public static QueueTicket forAppointment(UUID queueId, UUID userId, UUID appointmentId, LocalDate serviceDate,
            int tokenNumber, Instant now) {
        return new QueueTicket(queueId, userId, null, appointmentId, TicketSource.APPOINTMENT, serviceDate,
                tokenNumber, TicketStatus.BOOKED, TicketPriority.NORMAL, now);
    }

    /** A registered customer joins today's line immediately (customers cannot choose a priority). */
    public static QueueTicket walkIn(UUID queueId, UUID userId, LocalDate serviceDate, int tokenNumber, Instant now) {
        QueueTicket ticket = new QueueTicket(queueId, userId, null, null, TicketSource.WALK_IN, serviceDate,
                tokenNumber, TicketStatus.WAITING, TicketPriority.NORMAL, now);
        ticket.checkedInAt = now;
        return ticket;
    }

    /** Staff register a person without an account at the desk, optionally with a priority. */
    public static QueueTicket guestWalkIn(UUID queueId, String guestName, TicketPriority priority,
            LocalDate serviceDate, int tokenNumber, Instant now) {
        QueueTicket ticket = new QueueTicket(queueId, null, guestName, null, TicketSource.GUEST_WALK_IN, serviceDate,
                tokenNumber, TicketStatus.WAITING, priority, now);
        ticket.checkedInAt = now;
        return ticket;
    }

    public void checkIn(Instant now) {
        moveTo(TicketStatus.WAITING, "check in");
        checkedInAt = now;
        updatedAt = now;
    }

    public void call(Instant now) {
        moveTo(TicketStatus.CALLED, "call");
        calledAt = now;
        updatedAt = now;
    }

    public void startService(Instant now) {
        moveTo(TicketStatus.SERVING, "start serving");
        serviceStartedAt = now;
        updatedAt = now;
    }

    public void complete(Instant now) {
        moveTo(TicketStatus.COMPLETED, "complete");
        completedAt = now;
        updatedAt = now;
    }

    public void markNoShow(Instant now) {
        moveTo(TicketStatus.NO_SHOW, "mark as no-show");
        noShowAt = now;
        updatedAt = now;
    }

    public void cancel(Instant now) {
        moveTo(TicketStatus.CANCELLED, "cancel");
        cancelledAt = now;
        updatedAt = now;
    }

    /** Only tickets that have not reached the counter can be re-prioritised. */
    public void changePriority(TicketPriority newPriority, Instant now) {
        if (status != TicketStatus.BOOKED && status != TicketStatus.WAITING) {
            throw illegal("change the priority of");
        }
        priority = newPriority;
        updatedAt = now;
    }

    public void markApproachNotified(Instant now) {
        approachNotifiedAt = now;
        updatedAt = now;
    }

    private void moveTo(TicketStatus target, String action) {
        if (!status.canTransitionTo(target)) {
            throw illegal(action);
        }
        status = target;
    }

    private ApiException illegal(String action) {
        return new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                "Cannot " + action + " a ticket that is " + status.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                .with("currentStatus", status.name());
    }

    public UUID getId() {
        return id;
    }

    public UUID getQueueId() {
        return queueId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getGuestName() {
        return guestName;
    }

    public UUID getAppointmentId() {
        return appointmentId;
    }

    public TicketSource getSource() {
        return source;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public int getTokenNumber() {
        return tokenNumber;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public TicketPriority getPriority() {
        return priority;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCheckedInAt() {
        return checkedInAt;
    }

    public Instant getCalledAt() {
        return calledAt;
    }

    public Instant getServiceStartedAt() {
        return serviceStartedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getNoShowAt() {
        return noShowAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getApproachNotifiedAt() {
        return approachNotifiedAt;
    }
}
