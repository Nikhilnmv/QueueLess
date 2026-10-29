package com.queueless.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A registered person. Organization roles live in memberships, not on the user. */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Normalized (trimmed, lower-case) and globally unique. */
    @Column(nullable = false, length = 254, updatable = false)
    private String email;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    /** Delegating password-encoder hash, e.g. {bcrypt}$2a$10$... Never returned by the API. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled;

    /** Platform operators may provision organizations; they get no access to organization data. */
    @Column(name = "platform_admin", nullable = false)
    private boolean platformAdmin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public User(String normalizedEmail, String fullName, String passwordHash, Instant now) {
        this.email = normalizedEmail;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.enabled = true;
        this.platformAdmin = false;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void grantPlatformAdmin(Instant now) {
        this.platformAdmin = true;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isPlatformAdmin() {
        return platformAdmin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
