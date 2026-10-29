package com.queueless.realtime;

import com.queueless.common.config.QueueLessProperties;
import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Thread-safe registry of open SSE connections, keyed by queue (queue streams) and by user
 * (personal notification streams).
 *
 * <ul>
 *   <li>Connections are removed on completion, timeout, error or a failed write.</li>
 *   <li>A heartbeat comment is written periodically so dead connections are detected and proxies
 *       do not close idle streams.</li>
 *   <li>Writes happen on one dedicated thread, so a slow client never blocks a request thread
 *       and events reach each connection in publication order.</li>
 *   <li>On shutdown all streams are completed so browsers reconnect to the next instance.</li>
 * </ul>
 * Limitation: the registry lives in this JVM; see {@link RealtimeGateway} for multi-instance notes.
 */
@Component
public class SseEmitterRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    private final Map<UUID, Set<Subscription>> queueStreams = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Subscription>> userStreams = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> connectionsPerUser = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-sender");
        thread.setDaemon(true);
        return thread;
    });
    private final QueueLessProperties.Realtime config;
    private final TaskScheduler scheduler;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile boolean running;

    /**
     * One open connection. Deliberately a class with identity equality, not a record: a record's
     * generated hashCode() would include {@code streams}, the map that contains this subscription,
     * and recurse forever when the subscription is added to its set.
     */
    private static final class Subscription {
        private final UUID topic;
        private final UUID userId;
        private final SseEmitter emitter;
        private final Map<UUID, Set<Subscription>> streams;
        private final AtomicBoolean closed = new AtomicBoolean();

        Subscription(UUID topic, UUID userId, SseEmitter emitter, Map<UUID, Set<Subscription>> streams) {
            this.topic = topic;
            this.userId = userId;
            this.emitter = emitter;
            this.streams = streams;
        }

        UUID topic() {
            return topic;
        }

        UUID userId() {
            return userId;
        }

        SseEmitter emitter() {
            return emitter;
        }

        Map<UUID, Set<Subscription>> streams() {
            return streams;
        }

        AtomicBoolean closed() {
            return closed;
        }
    }

    public SseEmitterRegistry(QueueLessProperties properties, TaskScheduler scheduler) {
        this.config = properties.realtime();
        this.scheduler = scheduler;
    }

    public SseEmitter subscribeToQueue(UUID queueId, UUID userId, Object readyPayload) {
        return subscribe(queueStreams, queueId, userId, readyPayload);
    }

    public SseEmitter subscribeToUser(UUID userId, Object readyPayload) {
        return subscribe(userStreams, userId, userId, readyPayload);
    }

    void broadcastToQueue(UUID queueId, String eventName, Object payload) {
        dispatch(queueStreams.get(queueId), eventName, payload);
    }

    void broadcastToUser(UUID userId, String eventName, Object payload) {
        dispatch(userStreams.get(userId), eventName, payload);
    }

    public int openConnections() {
        return queueStreams.values().stream().mapToInt(Set::size).sum()
                + userStreams.values().stream().mapToInt(Set::size).sum();
    }

    public int subscribersOfQueue(UUID queueId) {
        Set<Subscription> subscriptions = queueStreams.get(queueId);
        return subscriptions == null ? 0 : subscriptions.size();
    }

    private SseEmitter subscribe(Map<UUID, Set<Subscription>> streams, UUID topic, UUID userId, Object readyPayload) {
        AtomicInteger count = connectionsPerUser.computeIfAbsent(userId, id -> new AtomicInteger());
        if (count.incrementAndGet() > config.maxConnectionsPerUser()) {
            count.decrementAndGet();
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS, "Too many open live connections. Close some tabs.")
                    .retryAfter(30);
        }
        SseEmitter emitter = new SseEmitter(config.emitterTimeout().toMillis());
        Subscription subscription = new Subscription(topic, userId, emitter, streams);
        // compute() keeps add and remove atomic per key, so a subscription can never be added to a
        // set that a concurrent removal is detaching from the map.
        streams.compute(topic, (key, set) -> {
            Set<Subscription> target = set != null ? set : ConcurrentHashMap.newKeySet();
            target.add(subscription);
            return target;
        });
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> {
            remove(subscription);
            emitter.complete();
        });
        emitter.onError(error -> remove(subscription));

        try {
            // Sent before the handler returns; Spring buffers it until the response is ready.
            emitter.send(SseEmitter.event().name("ready").reconnectTime(config.reconnectDelay().toMillis())
                    .data(readyPayload));
        } catch (IOException | IllegalStateException ex) {
            remove(subscription);
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    private void dispatch(Set<Subscription> subscriptions, String eventName, Object payload) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return;
        }
        List<Subscription> targets = List.copyOf(subscriptions);
        sender.execute(() -> targets.forEach(subscription ->
                write(subscription, SseEmitter.event().name(eventName).data(payload))));
    }

    private void write(Subscription subscription, SseEmitter.SseEventBuilder event) {
        if (subscription.closed().get()) {
            return;
        }
        try {
            subscription.emitter().send(event);
        } catch (IOException | IllegalStateException ex) {
            // The browser went away or the async request already ended.
            log.debug("Dropping SSE connection: {}", ex.getMessage());
            remove(subscription);
        }
    }

    private void remove(Subscription subscription) {
        if (!subscription.closed().compareAndSet(false, true)) {
            return;
        }
        subscription.streams().computeIfPresent(subscription.topic(), (key, set) -> {
            set.remove(subscription);
            return set.isEmpty() ? null : set;
        });
        connectionsPerUser.computeIfPresent(subscription.userId(),
                (key, counter) -> counter.decrementAndGet() <= 0 ? null : counter);
    }

    private void sendHeartbeats() {
        sender.execute(() -> {
            for (Map<UUID, Set<Subscription>> streams : List.of(queueStreams, userStreams)) {
                streams.values().forEach(set -> List.copyOf(set).forEach(subscription ->
                        write(subscription, SseEmitter.event().comment("heartbeat"))));
            }
        });
    }

    // --- lifecycle: start heartbeats; on shutdown close streams before the web server drains ---

    @Override
    public void start() {
        heartbeat = scheduler.scheduleAtFixedRate(this::sendHeartbeats, config.heartbeatInterval());
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        for (Map<UUID, Set<Subscription>> streams : List.of(queueStreams, userStreams)) {
            streams.values().forEach(set -> List.copyOf(set).forEach(subscription -> {
                remove(subscription);
                try {
                    subscription.emitter().complete();
                } catch (RuntimeException ignored) {
                    // already completed
                }
            }));
        }
        sender.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
