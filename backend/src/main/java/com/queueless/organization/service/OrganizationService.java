package com.queueless.organization.service;

import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import com.queueless.common.time.TimeProvider;
import com.queueless.organization.api.OrganizationResponse;
import com.queueless.organization.domain.Organization;
import com.queueless.organization.domain.OrganizationRepository;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class OrganizationService {

    private final OrganizationRepository organizations;
    private final TimeProvider time;

    public OrganizationService(OrganizationRepository organizations, TimeProvider time) {
        this.organizations = organizations;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public List<OrganizationResponse> listPublic(String search) {
        List<Organization> result = StringUtils.hasText(search)
                ? organizations.findByActiveTrueAndNameContainingIgnoreCaseOrderByNameAsc(search.trim())
                : organizations.findByActiveTrueOrderByNameAsc();
        return result.stream().map(OrganizationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public OrganizationResponse getPublic(String slug) {
        return OrganizationResponse.from(requireActiveBySlug(slug));
    }

    /** Inactive organizations are reported as not found, exactly like missing ones. */
    @Transactional(readOnly = true)
    public Organization requireActive(UUID organizationId) {
        return organizations.findById(organizationId)
                .filter(Organization::isActive)
                .orElseThrow(() -> ApiException.notFound("Organization"));
    }

    @Transactional(readOnly = true)
    public Organization requireActiveBySlug(String slug) {
        return organizations.findBySlug(slug)
                .filter(Organization::isActive)
                .orElseThrow(() -> ApiException.notFound("Organization"));
    }

    /** Locks the organization row for the rest of the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Organization lockActive(UUID organizationId) {
        return organizations.findByIdForUpdate(organizationId)
                .filter(Organization::isActive)
                .orElseThrow(() -> ApiException.notFound("Organization"));
    }

    @Transactional
    public Organization create(String name, String slug, String description, String timezone) {
        ZoneId zone = parseZone(timezone);
        if (organizations.existsBySlug(slug)) {
            throw new ApiException(ErrorCode.DUPLICATE_NAME, "An organization with this slug already exists");
        }
        return organizations.saveAndFlush(new Organization(name.trim(), slug, trimToNull(description), zone, time.now()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Organization updateProfile(Organization organization, String name, String description) {
        organization.updateProfile(name.trim(), trimToNull(description), time.now());
        return organization;
    }

    public static ZoneId parseZone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown time zone: " + timezone);
        }
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
