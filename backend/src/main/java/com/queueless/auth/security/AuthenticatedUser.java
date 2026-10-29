package com.queueless.auth.security;

import com.queueless.auth.domain.User;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The principal stored in the (database-backed) HTTP session.
 *
 * <p>It carries only identity. Organization roles are deliberately <em>not</em> authorities:
 * they are looked up from organization_memberships on every protected operation, so a revoked
 * membership takes effect immediately and a role in one organization can never leak into another.
 */
public final class AuthenticatedUser implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final String email;
    private final String fullName;
    private final boolean platformAdmin;
    private final boolean enabled;
    private String passwordHash;

    private AuthenticatedUser(UUID id, String email, String fullName, boolean platformAdmin, boolean enabled,
            String passwordHash) {
        this.id = id;
        this.email = email;
        this.fullName = fullName;
        this.platformAdmin = platformAdmin;
        this.enabled = enabled;
        this.passwordHash = passwordHash;
    }

    /** Principal used during password authentication (includes the hash to verify against). */
    public static AuthenticatedUser forAuthentication(User user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getFullName(), user.isPlatformAdmin(),
                user.isEnabled(), user.getPasswordHash());
    }

    /** Principal for an already-verified user (e.g. directly after registration). */
    public static AuthenticatedUser withoutCredentials(User user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getFullName(), user.isPlatformAdmin(),
                user.isEnabled(), null);
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

    public boolean isPlatformAdmin() {
        return platformAdmin;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return platformAdmin
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}
