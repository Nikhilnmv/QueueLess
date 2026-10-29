package com.queueless.membership.service;

import com.queueless.auth.domain.User;
import com.queueless.auth.domain.UserRepository;
import com.queueless.auth.service.EmailNormalizer;
import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import com.queueless.common.time.TimeProvider;
import com.queueless.membership.domain.MembershipRepository;
import com.queueless.membership.domain.MembershipRepository.MemberRow;
import com.queueless.membership.domain.MembershipRole;
import com.queueless.membership.domain.OrganizationMembership;
import com.queueless.membership.domain.Permission;
import com.queueless.organization.service.OrganizationService;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipService {

    private final MembershipRepository memberships;
    private final UserRepository users;
    private final OrganizationService organizations;
    private final AccessControlService access;
    private final TimeProvider time;

    public MembershipService(MembershipRepository memberships, UserRepository users,
            OrganizationService organizations, AccessControlService access, TimeProvider time) {
        this.memberships = memberships;
        this.users = users;
        this.organizations = organizations;
        this.access = access;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public List<MemberRow> listMembers(UUID organizationId, UUID actorId) {
        organizations.requireActive(organizationId);
        access.require(actorId, organizationId, Permission.MANAGE_MEMBERS);
        return memberships.findMembers(organizationId).stream()
                .sorted(Comparator.comparing((MemberRow m) -> -m.role().level()).thenComparing(MemberRow::fullName))
                .toList();
    }

    /** Adds an existing registered user (looked up by email) with the given role. */
    @Transactional
    public MemberRow addMember(UUID organizationId, UUID actorId, String email, MembershipRole role) {
        access.require(actorId, organizationId, Permission.MANAGE_MEMBERS);
        organizations.lockActive(organizationId);
        User user = users.findByEmail(EmailNormalizer.normalize(email))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "No registered user has this email address. Ask them to register first."));
        memberships.findByUserIdAndOrganizationId(user.getId(), organizationId).ifPresent(existing -> {
            throw new ApiException(ErrorCode.MEMBERSHIP_EXISTS,
                    "This user is already a member; change their role instead")
                    .with("membershipId", existing.getId());
        });
        OrganizationMembership membership =
                memberships.saveAndFlush(new OrganizationMembership(organizationId, user.getId(), role, time.now()));
        return toRow(membership, user);
    }

    @Transactional
    public MemberRow changeRole(UUID organizationId, UUID membershipId, UUID actorId, MembershipRole newRole) {
        access.require(actorId, organizationId, Permission.MANAGE_MEMBERS);
        // The organization lock serializes membership changes, so two administrators cannot
        // demote each other at the same moment and leave the organization without an admin.
        organizations.lockActive(organizationId);
        OrganizationMembership membership = requireMembership(organizationId, membershipId);
        if (membership.getRole() == MembershipRole.ADMIN && newRole != MembershipRole.ADMIN) {
            ensureAnotherAdminExists(organizationId);
        }
        membership.changeRole(newRole, time.now());
        User user = users.findById(membership.getUserId()).orElseThrow();
        return toRow(membership, user);
    }

    @Transactional
    public void removeMember(UUID organizationId, UUID membershipId, UUID actorId) {
        access.require(actorId, organizationId, Permission.MANAGE_MEMBERS);
        organizations.lockActive(organizationId);
        OrganizationMembership membership = requireMembership(organizationId, membershipId);
        if (membership.getRole() == MembershipRole.ADMIN) {
            ensureAnotherAdminExists(organizationId);
        }
        memberships.delete(membership);
    }

    /** Creates the first administrator of a newly provisioned organization. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addInitialAdmin(UUID organizationId, UUID userId) {
        memberships.save(new OrganizationMembership(organizationId, userId, MembershipRole.ADMIN, time.now()));
    }

    /** Marks the user as a customer of the organization (no-op if they already have any membership). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureCustomerMembership(UUID organizationId, UUID userId) {
        memberships.insertCustomerIfAbsent(organizationId, userId, time.now());
    }

    @Transactional(readOnly = true)
    public MemberCounts countMembers(UUID organizationId) {
        return new MemberCounts(
                memberships.countByOrganizationIdAndRole(organizationId, MembershipRole.ADMIN),
                memberships.countByOrganizationIdAndRole(organizationId, MembershipRole.STAFF),
                memberships.countByOrganizationIdAndRole(organizationId, MembershipRole.CUSTOMER));
    }

    public record MemberCounts(long admins, long staff, long customers) {}

    private OrganizationMembership requireMembership(UUID organizationId, UUID membershipId) {
        return memberships.findById(membershipId)
                .filter(m -> m.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> ApiException.notFound("Membership"));
    }

    private void ensureAnotherAdminExists(UUID organizationId) {
        if (memberships.countByOrganizationIdAndRole(organizationId, MembershipRole.ADMIN) <= 1) {
            throw new ApiException(ErrorCode.LAST_ADMIN,
                    "An organization must keep at least one administrator");
        }
    }

    private static MemberRow toRow(OrganizationMembership membership, User user) {
        return new MemberRow(membership.getId(), user.getId(), user.getEmail(), user.getFullName(),
                membership.getRole(), membership.getCreatedAt());
    }
}
