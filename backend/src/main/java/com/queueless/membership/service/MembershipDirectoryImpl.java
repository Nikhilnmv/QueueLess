package com.queueless.membership.service;

import com.queueless.auth.service.MembershipDirectory;
import com.queueless.membership.domain.MembershipRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements the auth module's port so "/api/auth/me" can list memberships without a module cycle. */
@Service
class MembershipDirectoryImpl implements MembershipDirectory {

    private final MembershipRepository memberships;

    MembershipDirectoryImpl(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipSummary> membershipsOf(UUID userId) {
        return memberships.findMembershipsOfUser(userId).stream()
                .map(row -> new MembershipSummary(row.organizationId(), row.organizationName(),
                        row.organizationSlug(), row.role().name()))
                .toList();
    }
}
