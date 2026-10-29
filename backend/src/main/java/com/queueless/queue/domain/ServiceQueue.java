package com.queueless.queue.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A service counter (e.g. "General OPD") with its schedule and booking configuration.
 *
 * <p>The queue row doubles as the lock that serializes every change to the queue's tickets and
 * appointments (see QueueLockService). It is named ServiceQueue to avoid confusion with java.util.Queue.
 */
@Entity
@Table(name = "service_queues")
public class ServiceQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "token_prefix", nullable = false, length = 5)
    private String tokenPrefix;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QueueStatus status;

    @Column(name = "booking_enabled", nullable = false)
    private boolean bookingEnabled;

    /** Organization-local opening time; appointment slots start here. */
    @Column(name = "opening_time", nullable = false)
    private LocalTime openingTime;

    /** Organization-local closing time; the last slot must end by this time. */
    @Column(name = "closing_time", nullable = false)
    private LocalTime closingTime;

    @Column(name = "slot_duration_minutes", nullable = false)
    private int slotDurationMinutes;

    @Column(name = "capacity_per_slot", nullable = false)
    private int capacityPerSlot;

    /** Fallback service time used for wait estimates until enough history exists. */
    @Column(name = "default_service_minutes", nullable = false)
    private int defaultServiceMinutes;

    @Column(name = "state_version", nullable = false)
    private long stateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServiceQueue() {
    }

    public ServiceQueue(UUID organizationId, QueueSettings settings, Instant now) {
        this.organizationId = organizationId;
        this.status = QueueStatus.CLOSED;
        this.stateVersion = 0;
        this.createdAt = now;
        apply(settings, now);
    }

    /** Applies administrator configuration. Existing bookings keep their times and are honoured. */
    public void apply(QueueSettings settings, Instant now) {
        this.name = settings.name();
        this.description = settings.description();
        this.tokenPrefix = settings.tokenPrefix();
        this.openingTime = settings.openingTime();
        this.closingTime = settings.closingTime();
        this.slotDurationMinutes = settings.slotDurationMinutes();
        this.capacityPerSlot = settings.capacityPerSlot();
        this.defaultServiceMinutes = settings.defaultServiceMinutes();
        this.bookingEnabled = settings.bookingEnabled();
        this.updatedAt = now;
    }

    public void changeStatus(QueueStatus newStatus, Instant now) {
        this.status = newStatus;
        this.updatedAt = now;
    }

    /** Records that the live queue changed; must be called while holding the queue lock. */
    public long bumpStateVersion(Instant now) {
        this.stateVersion++;
        this.updatedAt = now;
        return stateVersion;
    }

    /** Walk-ins and check-ins are accepted while OPEN or PAUSED. */
    public boolean acceptsNewArrivals() {
        return status == QueueStatus.OPEN || status == QueueStatus.PAUSED;
    }

    public boolean canCallNext() {
        return status == QueueStatus.OPEN;
    }

    public String displayToken(int tokenNumber) {
        return tokenPrefix + "-" + String.format("%03d", tokenNumber);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public QueueStatus getStatus() {
        return status;
    }

    public boolean isBookingEnabled() {
        return bookingEnabled;
    }

    public LocalTime getOpeningTime() {
        return openingTime;
    }

    public LocalTime getClosingTime() {
        return closingTime;
    }

    public int getSlotDurationMinutes() {
        return slotDurationMinutes;
    }

    public int getCapacityPerSlot() {
        return capacityPerSlot;
    }

    public int getDefaultServiceMinutes() {
        return defaultServiceMinutes;
    }

    public long getStateVersion() {
        return stateVersion;
    }
}
