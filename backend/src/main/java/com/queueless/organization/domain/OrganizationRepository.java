package com.queueless.organization.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Organization> findByActiveTrueOrderByNameAsc();

    /** Spring Data escapes % and _ in the search term for "Containing" queries. */
    List<Organization> findByActiveTrueAndNameContainingIgnoreCaseOrderByNameAsc(String name);

    /** Serializes membership changes of one organization (last-administrator rule). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.id = :id")
    Optional<Organization> findByIdForUpdate(UUID id);
}
