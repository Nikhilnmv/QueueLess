package com.queueless.notification.service;

import com.queueless.common.error.ApiException;
import com.queueless.common.time.TimeProvider;
import com.queueless.notification.api.NotificationDtos.MarkAllReadResponse;
import com.queueless.notification.api.NotificationDtos.NotificationListResponse;
import com.queueless.notification.api.NotificationDtos.NotificationResponse;
import com.queueless.notification.domain.Notification;
import com.queueless.notification.domain.NotificationRepository;
import com.queueless.notification.domain.NotificationType;
import com.queueless.realtime.UserNotificationEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private static final int MAX_LIST_SIZE = 100;

    private final NotificationRepository notifications;
    private final ApplicationEventPublisher events;
    private final TimeProvider time;

    public NotificationService(NotificationRepository notifications, ApplicationEventPublisher events,
            TimeProvider time) {
        this.notifications = notifications;
        this.events = events;
        this.time = time;
    }

    /**
     * Stores a notification as part of the caller's transaction (so it exists if and only if the
     * queue change that caused it commits) and schedules a push to the user's live stream.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Notification notify(UUID userId, UUID ticketId, NotificationType type, String title, String message) {
        Notification notification =
                notifications.save(new Notification(userId, ticketId, type, title, message, time.now()));
        events.publishEvent(new UserNotificationEvent(userId, notification.getId(), type.name(), ticketId));
        return notification;
    }

    @Transactional(readOnly = true)
    public NotificationListResponse list(UUID userId, boolean unreadOnly, int limit) {
        Limit max = Limit.of(Math.max(1, Math.min(limit, MAX_LIST_SIZE)));
        List<Notification> items = unreadOnly
                ? notifications.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, max)
                : notifications.findByUserIdOrderByCreatedAtDesc(userId, max);
        return new NotificationListResponse(items.stream().map(NotificationResponse::from).toList(),
                notifications.countByUserIdAndReadAtIsNull(userId));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    /** Only the owner can mark a notification read; other users get 404. Idempotent. */
    @Transactional
    public NotificationResponse markRead(UUID userId, UUID notificationId) {
        Notification notification = notifications.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> ApiException.notFound("Notification"));
        notification.markRead(time.now());
        return NotificationResponse.from(notification);
    }

    @Transactional
    public MarkAllReadResponse markAllRead(UUID userId) {
        int updated = notifications.markAllRead(userId, time.now());
        return new MarkAllReadResponse(updated, notifications.countByUserIdAndReadAtIsNull(userId));
    }
}
