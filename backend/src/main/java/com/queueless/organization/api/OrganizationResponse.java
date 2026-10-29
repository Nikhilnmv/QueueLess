package com.queueless.organization.api;

import com.queueless.organization.domain.Organization;
import java.util.UUID;

/** Public organization profile (safe to show to anyone). */
public record OrganizationResponse(UUID id, String name, String slug, String description, String timezone) {

    public static OrganizationResponse from(Organization organization) {
        return new OrganizationResponse(organization.getId(), organization.getName(), organization.getSlug(),
                organization.getDescription(), organization.getTimezone());
    }
}
