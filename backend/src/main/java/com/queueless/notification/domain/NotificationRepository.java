package com.queueless.notification.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Served by ix_notifications_user_created. */
    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Limit limit);

    List<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(UUID userId, Limit limit);

    /** Served by the partial index ix_notifications_user_unread. */
    long countByUserIdAndReadAtIsNull(UUID userId);

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByTicketIdAndType(UUID ticketId, NotificationType type);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(UUID userId, Instant now);
}
