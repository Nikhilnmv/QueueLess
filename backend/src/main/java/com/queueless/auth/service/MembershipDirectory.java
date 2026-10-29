package com.queueless.auth.service;

import java.util.List;
import java.util.UUID;

/**
 * Port implemented by the membership module. The auth module needs a user's memberships for
 * "who am I" responses but must not depend on the membership module (which depends on auth),
 * so the dependency is inverted through this interface.
 */
public interface MembershipDirectory {

    List<MembershipSummary> membershipsOf(UUID userId);

    record MembershipSummary(UUID organizationId, String organizationName, String organizationSlug, String role) {}
}
