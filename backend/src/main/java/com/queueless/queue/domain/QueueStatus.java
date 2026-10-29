package com.queueless.queue.domain;

/**
 * Live operating state of a queue, controlled by staff.
 *
 * <ul>
 *   <li>OPEN - walk-ins and check-ins accepted, staff can call the next ticket.</li>
 *   <li>PAUSED - customers can still join and check in (they keep their place), but calling is on hold.</li>
 *   <li>CLOSED - no walk-ins, check-ins or calls. Tickets already called can still be finished.</li>
 * </ul>
 * Advance appointment booking is controlled separately by {@code bookingEnabled}.
 */
public enum QueueStatus {
    OPEN,
    PAUSED,
    CLOSED
}
