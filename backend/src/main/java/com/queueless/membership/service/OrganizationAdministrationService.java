package com.queueless.membership.service;

import com.queueless.auth.domain.User;
import com.queueless.auth.domain.UserRepository;
import com.queueless.auth.service.EmailNormalizer;
import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import com.queueless.membership.api.MembershipDtos.OrganizationManagementResponse;
import com.queueless.membership.domain.MembershipRepository.MemberRow;
import com.queueless.membership.domain.MembershipRole;
import com.queueless.membership.domain.Permission;
import com.queueless.organization.api.OrganizationResponse;
import com.queueless.organization.domain.Organization;
import com.queueless.organization.service.OrganizationService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use cases behind the organization-admin and platform-admin endpoints. */
@Service
public class OrganizationAdministrationService {

    private final OrganizationService organizations;
    private final MembershipService memberships;
    private final AccessControlService access;
    private final UserRepository users;

    public OrganizationAdministrationService(OrganizationService organizations, MembershipService memberships,
            AccessControlService access, UserRepository users) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.access = access;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public OrganizationManagementResponse overview(UUID organizationId, UUID actorId) {
        Organization organization = organizations.requireActive(organizationId);
        MembershipRole role = access.require(actorId, organizationId, Permission.VIEW_ORGANIZATION_MANAGEMENT);
        return new OrganizationManagementResponse(OrganizationResponse.from(organization), role,
                memberships.countMembers(organizationId));
    }

    @Transactional
    public OrganizationManagementResponse updateProfile(UUID organizationId, UUID actorId, String name,
            String description) {
        MembershipRole role = access.require(actorId, organizationId, Permission.UPDATE_ORGANIZATION);
        Organization organization = organizations.lockActive(organizationId);
        organizations.updateProfile(organization, name, description);
        return new OrganizationManagementResponse(OrganizationResponse.from(organization), role,
                memberships.countMembers(organizationId));
    }

    public List<MemberRow> members(UUID organizationId, UUID actorId) {
        return memberships.listMembers(organizationId, actorId);
    }

    public MemberRow addMember(UUID organizationId, UUID actorId, String email, MembershipRole role) {
        return memberships.addMember(organizationId, actorId, email, role);
    }

    public MemberRow changeRole(UUID organizationId, UUID membershipId, UUID actorId, MembershipRole role) {
        return memberships.changeRole(organizationId, membershipId, actorId, role);
    }

    public void removeMember(UUID organizationId, UUID membershipId, UUID actorId) {
        memberships.removeMember(organizationId, membershipId, actorId);
    }

    /**
     * Platform provisioning: creates an organization and makes an existing user its first ADMIN.
     * The platform admin gains no membership and therefore no access to the organization's data.
     */
    @Transactional
    public OrganizationResponse provisionOrganization(String name, String slug, String description, String timezone,
            String adminEmail) {
        User admin = users.findByEmail(EmailNormalizer.normalize(adminEmail))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "No registered user has the administrator email address"));
        Organization organization = organizations.create(name, slug, description, timezone);
        memberships.addInitialAdmin(organization.getId(), admin.getId());
        return OrganizationResponse.from(organization);
    }
}
