package com.queueless.appointment.domain;

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

/** A booked slot. Its queue ticket (token) is created in the same transaction as the booking. */
@Entity
@Table(name = "appointments")
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "queue_id", nullable = false, updatable = false)
    private UUID queueId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Slot start as an instant; always aligned with the queue's slot grid when booked. */
    @Column(name = "scheduled_at", nullable = false, updatable = false)
    private Instant scheduledAt;

    /** Organization-local date of scheduledAt. */
    @Column(name = "service_date", nullable = false, updatable = false)
    private LocalDate serviceDate;

    @Column(name = "booking_reference", nullable = false, length = 16, updatable = false)
    private String bookingReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AppointmentStatus status;

    @Column(name = "idempotency_key", updatable = false)
    private UUID idempotencyKey;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by_user_id")
    private UUID cancelledByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Appointment() {
    }

    public Appointment(UUID queueId, UUID userId, Instant scheduledAt, LocalDate serviceDate, String bookingReference,
            UUID idempotencyKey, Instant now) {
        this.queueId = queueId;
        this.userId = userId;
        this.scheduledAt = scheduledAt;
        this.serviceDate = serviceDate;
        this.bookingReference = bookingReference;
        this.idempotencyKey = idempotencyKey;
        this.status = AppointmentStatus.BOOKED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    // The methods below mirror ticket transitions and are idempotent: repeating one is a no-op.

    public void markCheckedIn(Instant now) {
        moveTo(AppointmentStatus.CHECKED_IN, AppointmentStatus.BOOKED, now);
    }

    public void markCompleted(Instant now) {
        moveTo(AppointmentStatus.COMPLETED, AppointmentStatus.CHECKED_IN, now);
    }

    public void markNoShow(Instant now) {
        moveTo(AppointmentStatus.NO_SHOW, AppointmentStatus.CHECKED_IN, now);
    }

    public void cancel(Instant now, UUID actorUserId) {
        if (status == AppointmentStatus.CANCELLED) {
            return;
        }
        if (!status.isActive()) {
            throw illegal(AppointmentStatus.CANCELLED);
        }
        status = AppointmentStatus.CANCELLED;
        cancelledAt = now;
        cancelledByUserId = actorUserId;
        updatedAt = now;
    }

    private void moveTo(AppointmentStatus target, AppointmentStatus requiredCurrent, Instant now) {
        if (status == target) {
            return;
        }
        if (status != requiredCurrent) {
            throw illegal(target);
        }
        status = target;
        updatedAt = now;
    }

    private ApiException illegal(AppointmentStatus target) {
        return new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "An appointment that is "
                + status.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " cannot become "
                + target.name().toLowerCase(Locale.ROOT).replace('_', ' '))
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

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public UUID getCancelledByUserId() {
        return cancelledByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
