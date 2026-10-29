package com.queueless.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/** A tenant: a clinic, office, lab or help desk that runs one or more service queues. */
@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 60, updatable = false)
    private String slug;

    @Column(length = 500)
    private String description;

    /**
     * IANA time zone (e.g. Asia/Kolkata). Service dates, opening hours, appointment slots and daily
     * token numbering are all interpreted in this zone. Fixed after creation because changing it
     * would silently move existing bookings.
     */
    @Column(nullable = false, length = 64, updatable = false)
    private String timezone;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Organization() {
    }

    public Organization(String name, String slug, String description, ZoneId zone, Instant now) {
        this.name = name;
        this.slug = slug;
        this.description = description;
        this.timezone = zone.getId();
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateProfile(String name, String description, Instant now) {
        this.name = name;
        this.description = description;
        this.updatedAt = now;
    }

    public ZoneId zoneId() {
        return ZoneId.of(timezone);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public String getTimezone() {
        return timezone;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
