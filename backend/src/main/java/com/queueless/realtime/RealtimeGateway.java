package com.queueless.realtime;

import java.util.UUID;

/**
 * Transport abstraction for pushing messages to connected browsers.
 *
 * <p>The current implementation ({@link SseRealtimeGateway}) delivers to SSE connections held by
 * this JVM only. A multi-instance deployment would add an implementation that publishes to a
 * shared channel (PostgreSQL LISTEN/NOTIFY or Redis Pub/Sub) and lets every instance forward to its
 * own local connections; queue business logic would not change.
 */
public interface RealtimeGateway {

    void sendToQueue(UUID queueId, String eventName, Object payload);

    void sendToUser(UUID userId, String eventName, Object payload);
}
