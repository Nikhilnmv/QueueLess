package com.queueless.queue.domain;

import java.time.LocalTime;

/** Validated administrator configuration of a queue. */
public record QueueSettings(
        String name,
        String description,
        String tokenPrefix,
        LocalTime openingTime,
        LocalTime closingTime,
        int slotDurationMinutes,
        int capacityPerSlot,
        int defaultServiceMinutes,
        boolean bookingEnabled) {
}
