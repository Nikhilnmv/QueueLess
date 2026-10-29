package com.queueless.notification.api;

import com.queueless.auth.security.AuthenticatedUser;
import com.queueless.notification.api.NotificationDtos.MarkAllReadResponse;
import com.queueless.notification.api.NotificationDtos.NotificationListResponse;
import com.queueless.notification.api.NotificationDtos.NotificationResponse;
import com.queueless.notification.api.NotificationDtos.UserStreamReady;
import com.queueless.notification.service.NotificationService;
import com.queueless.realtime.SseEmitterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The current user's notifications and personal live stream. */
@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/me")
public class NotificationController {

    private final NotificationService notifications;
    private final SseEmitterRegistry registry;

    public NotificationController(NotificationService notifications, SseEmitterRegistry registry) {
        this.notifications = notifications;
        this.registry = registry;
    }

    @Operation(summary = "List my notifications (newest first) with the unread count")
    @GetMapping("/notifications")
    public NotificationListResponse list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "50") int limit) {
        return notifications.list(user.getId(), unreadOnly, limit);
    }

    @Operation(summary = "Mark one of my notifications as read")
    @PostMapping("/notifications/{notificationId}/read")
    public NotificationResponse markRead(@PathVariable UUID notificationId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return notifications.markRead(user.getId(), notificationId);
    }

    @Operation(summary = "Mark all my notifications as read")
    @PostMapping("/notifications/read-all")
    public MarkAllReadResponse markAllRead(@AuthenticationPrincipal AuthenticatedUser user) {
        return notifications.markAllRead(user.getId());
    }

    @Operation(summary = "Personal event stream: 'notification' events when a notification is stored")
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal AuthenticatedUser user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return registry.subscribeToUser(user.getId(),
                new UserStreamReady(user.getId(), notifications.unreadCount(user.getId())));
    }
}
