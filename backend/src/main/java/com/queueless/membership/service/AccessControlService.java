package com.queueless.membership.service;

import com.queueless.common.error.ApiException;
import com.queueless.membership.domain.MembershipRepository;
import com.queueless.membership.domain.MembershipRole;
import com.queueless.membership.domain.Permission;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-aware authorization. Every staff/admin operation resolves the organization from the
 * resource being touched (never from client input alone) and asks this service whether the
 * current user holds the required permission <em>in that organization</em>.
 */
@Service
public class AccessControlService {

    private final MembershipRepository memberships;

    public AccessControlService(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public Optional<MembershipRole> roleOf(UUID userId, UUID organizationId) {
        return memberships.findRole(userId, organizationId);
    }

    @Transactional(readOnly = true)
    public boolean has(UUID userId, UUID organizationId, Permission permission) {
        return permission.isGrantedTo(roleOf(userId, organizationId).orElse(null));
    }

    /** Returns the caller's role, or throws 403 if the permission is not granted in this organization. */
    @Transactional(readOnly = true)
    public MembershipRole require(UUID userId, UUID organizationId, Permission permission) {
        MembershipRole role = roleOf(userId, organizationId).orElse(null);
        if (!permission.isGrantedTo(role)) {
            throw ApiException.accessDenied("You do not have permission to " + permission.description()
                    + " in this organization");
        }
        return role;
    }
}
