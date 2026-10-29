package com.queueless.appointment.service;

import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import com.queueless.queue.domain.ServiceQueue;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Pure validation of a requested appointment time (no database access). */
public final class BookingRules {

    private BookingRules() {}

    public record ValidatedSlot(LocalDate serviceDate, LocalTime localStart, Instant startsAt) {}

    /**
     * The request is valid when the slot (in the organization's time zone):
     * <ul>
     *   <li>has not started yet,</li>
     *   <li>is no more than {@code maxDaysAhead} days after today, and</li>
     *   <li>is exactly one of the queue's slot start times.</li>
     * </ul>
     * Capacity is checked separately, under the queue lock.
     */
    public static ValidatedSlot validate(ServiceQueue queue, ZoneId zone, Instant requested, Instant now,
            int maxDaysAhead) {
        if (requested == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "scheduledAt is required");
        }
        ZonedDateTime local = requested.atZone(zone);
        LocalDate date = local.toLocalDate();
        LocalTime time = local.toLocalTime();
        LocalDate today = LocalDate.ofInstant(now, zone);

        if (!requested.isAfter(now)) {
            throw new ApiException(ErrorCode.INVALID_SLOT, "That slot has already started; choose a later one");
        }
        if (date.isAfter(today.plusDays(maxDaysAhead))) {
            throw new ApiException(ErrorCode.INVALID_SLOT,
                    "Appointments can be booked at most " + maxDaysAhead + " days in advance");
        }
        if (!SlotSchedule.isSlotStart(time, queue.getOpeningTime(), queue.getClosingTime(),
                queue.getSlotDurationMinutes())) {
            throw new ApiException(ErrorCode.INVALID_SLOT,
                    "The requested time is not one of this queue's appointment slots");
        }
        return new ValidatedSlot(date, time, requested);
    }
}
