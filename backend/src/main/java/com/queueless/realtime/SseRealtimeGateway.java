package com.queueless.realtime;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Single-instance transport: delivers directly to the SSE connections held by this JVM. */
@Component
class SseRealtimeGateway implements RealtimeGateway {

    private final SseEmitterRegistry registry;

    SseRealtimeGateway(SseEmitterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void sendToQueue(UUID queueId, String eventName, Object payload) {
        registry.broadcastToQueue(queueId, eventName, payload);
    }

    @Override
    public void sendToUser(UUID userId, String eventName, Object payload) {
        registry.broadcastToUser(userId, eventName, payload);
    }
}
