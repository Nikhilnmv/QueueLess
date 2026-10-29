package com.queueless.realtime;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Test gateway: records every message handed to the transport and forwards it to the real SSE
 * gateway, so tests can both count published events and receive them over HTTP.
 * Lives in the realtime package to reach the package-private SSE gateway.
 */
public class RecordingRealtimeGateway implements RealtimeGateway {

    public record Sent(String channel, UUID target, String eventName, Object payload) {}

    private final RealtimeGateway delegate;
    private final ConcurrentLinkedQueue<Sent> sent = new ConcurrentLinkedQueue<>();

    public RecordingRealtimeGateway(SseEmitterRegistry registry) {
        this.delegate = new SseRealtimeGateway(registry);
    }

    @Override
    public void sendToQueue(UUID queueId, String eventName, Object payload) {
        sent.add(new Sent("queue", queueId, eventName, payload));
        delegate.sendToQueue(queueId, eventName, payload);
    }

    @Override
    public void sendToUser(UUID userId, String eventName, Object payload) {
        sent.add(new Sent("user", userId, eventName, payload));
        delegate.sendToUser(userId, eventName, payload);
    }

    public List<Sent> sentToQueue(UUID queueId) {
        return sent.stream().filter(s -> s.channel().equals("queue") && s.target().equals(queueId)).toList();
    }

    public List<Sent> sentToUser(UUID userId) {
        return sent.stream().filter(s -> s.channel().equals("user") && s.target().equals(userId)).toList();
    }

    /** Reason of a queue-changed payload (the payload type is package-private). */
    public static QueueChangeReason reasonOf(Sent message) {
        return ((RealtimeEventRelay.QueueChangedPayload) message.payload()).reason();
    }
}
