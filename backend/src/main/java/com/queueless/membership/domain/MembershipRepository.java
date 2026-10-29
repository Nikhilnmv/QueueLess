package com.queueless.membership.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<OrganizationMembership, UUID> {

    /** The per-request authorization lookup (served by uq_memberships_user_org). */
    @Query("select m.role from OrganizationMembership m where m.userId = :userId and m.organizationId = :organizationId")
    Optional<MembershipRole> findRole(UUID userId, UUID organizationId);

    Optional<OrganizationMembership> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);

    long countByOrganizationIdAndRole(UUID organizationId, MembershipRole role);

    record MemberRow(UUID membershipId, UUID userId, String email, String fullName, MembershipRole role,
                     Instant createdAt) {}

    @Query("""
            select new com.queueless.membership.domain.MembershipRepository$MemberRow(
                m.id, m.userId, u.email, u.fullName, m.role, m.createdAt)
            from OrganizationMembership m join User u on u.id = m.userId
            where m.organizationId = :organizationId
            order by u.fullName""")
    List<MemberRow> findMembers(UUID organizationId);

    record MembershipOfUserRow(UUID organizationId, String organizationName, String organizationSlug,
                               MembershipRole role) {}

    @Query("""
            select new com.queueless.membership.domain.MembershipRepository$MembershipOfUserRow(
                o.id, o.name, o.slug, m.role)
            from OrganizationMembership m join Organization o on o.id = m.organizationId
            where m.userId = :userId and o.active = true
            order by o.name""")
    List<MembershipOfUserRow> findMembershipsOfUser(UUID userId);

    /**
     * Records that a user is a customer of an organization the first time they book or join a
     * queue. ON CONFLICT DO NOTHING keeps concurrent first visits safe and never downgrades an
     * existing STAFF/ADMIN membership.
     */
    @Modifying
    @Query(value = """
            insert into organization_memberships (id, organization_id, user_id, role, created_at, updated_at)
            values (gen_random_uuid(), :organizationId, :userId, 'CUSTOMER', :now, :now)
            on conflict (user_id, organization_id) do nothing""", nativeQuery = true)
    int insertCustomerIfAbsent(UUID organizationId, UUID userId, Instant now);
}
