package com.queueless.queue.service;

import com.queueless.common.error.ApiException;
import com.queueless.queue.domain.ServiceQueue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acquires the per-queue pessimistic lock that serializes all writes to one queue: token
 * allocation, slot-capacity checks, check-ins and every staff operation. Different queues never
 * block each other. Each transaction locks at most one queue row, so these locks cannot deadlock.
 */
@Service
public class QueueLockService {

    private final EntityManager entityManager;

    public QueueLockService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Locks the queue row until the surrounding transaction commits or rolls back. Concurrent callers
     * wait here (bounded by lock_timeout), then continue one at a time.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedQueue lock(UUID queueId) {
        ServiceQueue queue = entityManager.find(ServiceQueue.class, queueId);
        if (queue == null) {
            throw ApiException.notFound("Queue");
        }
        // refresh() issues SELECT ... FOR UPDATE *and* reloads the row. A plain locking query would
        // return an already-managed instance unchanged, i.e. possibly state read before the lock.
        entityManager.refresh(queue, LockModeType.PESSIMISTIC_WRITE);
        return new LockedQueue(queue);
    }
}
