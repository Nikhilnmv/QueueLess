package com.queueless.realtime;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges domain events to the realtime transport. AFTER_COMMIT guarantees that a browser is
 * never told about a change that was rolled back; if the transaction fails, nothing is sent.
 */
@Component
class RealtimeEventRelay {

    static final String QUEUE_CHANGED = "queue-changed";
    static final String NOTIFICATION = "notification";

    /** Minimal payload: subscribers re-fetch the authorized snapshot instead of trusting the event. */
    record QueueChangedPayload(UUID queueId, long version, QueueChangeReason reason, Instant occurredAt) {}

    record NotificationPayload(UUID notificationId, String type, UUID ticketId) {}

    private final RealtimeGateway gateway;

    RealtimeEventRelay(RealtimeGateway gateway) {
        this.gateway = gateway;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onQueueChanged(QueueChangedEvent event) {
        gateway.sendToQueue(event.queueId(), QUEUE_CHANGED,
                new QueueChangedPayload(event.queueId(), event.version(), event.reason(), event.occurredAt()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onNotification(UserNotificationEvent event) {
        gateway.sendToUser(event.userId(), NOTIFICATION,
                new NotificationPayload(event.notificationId(), event.type(), event.ticketId()));
    }
}
