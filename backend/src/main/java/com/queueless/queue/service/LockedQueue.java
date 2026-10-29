package com.queueless.queue.service;

import com.queueless.queue.domain.ServiceQueue;
import java.util.UUID;

/**
 * Proof that the current transaction holds the queue's row lock ({@code SELECT ... FOR UPDATE}).
 *
 * <p>The constructor is package-private, so the only way to obtain one is
 * {@link QueueLockService#lock(UUID)}. Methods that must run under the lock (token allocation,
 * ticket transitions) take a LockedQueue parameter, which makes "forgot to lock" a compile error.
 * It is only meaningful inside the transaction that created it.
 */
public final class LockedQueue {

    private final ServiceQueue queue;

    LockedQueue(ServiceQueue queue) {
        this.queue = queue;
    }

    public ServiceQueue queue() {
        return queue;
    }

    public UUID id() {
        return queue.getId();
    }
}
