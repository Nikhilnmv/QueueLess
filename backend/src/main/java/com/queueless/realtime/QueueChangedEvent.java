package com.queueless.realtime;

import java.time.Instant;
import java.util.UUID;

/**
 * Published (as a Spring application event) inside the transaction that changed a queue.
 * {@link RealtimeEventRelay} forwards it to subscribers only after that transaction commits.
 *
 * @param version the queue's state_version after the change; clients use it to discard stale updates
 */
public record QueueChangedEvent(UUID queueId, long version, QueueChangeReason reason, Instant occurredAt) {
}
