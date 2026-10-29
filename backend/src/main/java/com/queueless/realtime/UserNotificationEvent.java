package com.queueless.realtime;

import java.util.UUID;

/** A notification was stored for a user; relayed to that user's own event stream after commit. */
public record UserNotificationEvent(UUID userId, UUID notificationId, String type, UUID ticketId) {
}
