package com.queueless.notification.api;

import com.queueless.notification.domain.Notification;
import com.queueless.notification.domain.NotificationType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {}

    public record NotificationResponse(UUID id, NotificationType type, String title, String message, UUID ticketId,
                                       Instant createdAt, Instant readAt) {

        public static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getTicketId(),
                    n.getCreatedAt(), n.getReadAt());
        }
    }

    public record NotificationListResponse(List<NotificationResponse> items, long unreadCount) {}

    public record UnreadCountResponse(long unreadCount) {}

    public record MarkAllReadResponse(int updated, long unreadCount) {}

    /** First event on the personal stream. */
    public record UserStreamReady(UUID userId, long unreadCount) {}
}
