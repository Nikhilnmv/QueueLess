package com.queueless.queue.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ServiceQueueRepository extends JpaRepository<ServiceQueue, UUID> {

    List<ServiceQueue> findByOrganizationIdOrderByNameAsc(UUID organizationId);

    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);

    /** Scalar lookup that does not load the entity (used for authorization before locking). */
    @Query("select q.organizationId from ServiceQueue q where q.id = :queueId")
    Optional<UUID> findOrganizationId(UUID queueId);
}
