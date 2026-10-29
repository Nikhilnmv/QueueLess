package com.queueless.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queueless.appointment.service.BookingRules.ValidatedSlot;
import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import com.queueless.queue.domain.QueueSettings;
import com.queueless.queue.domain.ServiceQueue;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingRulesTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = ZonedDateTime.of(2026, 3, 10, 10, 0, 0, 0, KOLKATA).toInstant();

    private static ServiceQueue queue(LocalTime opening, LocalTime closing, int slotMinutes) {
        return new ServiceQueue(UUID.randomUUID(),
                new QueueSettings("Q", null, "Q", opening, closing, slotMinutes, 2, 10, true), NOW);
    }

    private static Instant local(int year, int month, int day, int hour, int minute, ZoneId zone) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant();
    }

    private static ErrorCode codeOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException ex) {
            return ex.code();
        }
    }

    @Test
    void acceptsAFutureGridAlignedSlotAndReportsTheLocalServiceDate() {
        ServiceQueue queue = queue(LocalTime.of(9, 0), LocalTime.of(17, 0), 15);
        ValidatedSlot slot = BookingRules.validate(queue, KOLKATA, local(2026, 3, 11, 9, 45, KOLKATA), NOW, 14);
        assertThat(slot.serviceDate()).isEqualTo(LocalDate.of(2026, 3, 11));
        assertThat(slot.localStart()).isEqualTo(LocalTime.of(9, 45));
    }

    @Test
    void rejectsSlotsThatHaveStarted() {
        ServiceQueue queue = queue(LocalTime.of(9, 0), LocalTime.of(17, 0), 15);
        assertThat(codeOf(() -> BookingRules.validate(queue, KOLKATA, local(2026, 3, 10, 9, 45, KOLKATA), NOW, 14)))
                .isEqualTo(ErrorCode.INVALID_SLOT);
        assertThat(codeOf(() -> BookingRules.validate(queue, KOLKATA, NOW, NOW, 14))).isEqualTo(ErrorCode.INVALID_SLOT);
        assertThat(codeOf(() -> BookingRules.validate(queue, KOLKATA, local(2026, 3, 10, 10, 15, KOLKATA), NOW, 14))).isNull();
    }

    @Test
    void rejectsSlotsBeyondTheBookingHorizon() {
        ServiceQueue queue = queue(LocalTime.of(9, 0), LocalTime.of(17, 0), 15);
        assertThat(codeOf(() -> BookingRules.validate(queue, KOLKATA, local(2026, 3, 24, 9, 0, KOLKATA), NOW, 14))).isNull();
        assertThat(codeOf(() -> BookingRules.validate(queue, KOLKATA, local(2026, 3, 25, 9, 0, KOLKATA), NOW, 14)))
                .isEqualTo(ErrorCode.INVALID_SLOT);
    }

    @Test
    void rejectsTimesOffTheSlotGrid() {
        ServiceQueue queue = queue(LocalTime.of(9, 0), LocalTime.of(17, 0), 20);
        assertThat(codeOf(() -> BookingRules.validate(queue, KOLKATA, local(2026, 3, 11, 9, 20, KOLKATA), NOW, 14))).isNull();
        assertThat(codeOf(() -> BookingRules.validate(queue, KOLKATA, local(2026, 3, 11, 9, 15, KOLKATA), NOW, 14)))
                .isEqualTo(ErrorCode.INVALID_SLOT);
    }

    @Test
    void interpretsTheRequestInTheOrganizationsTimeZone() {
        // 09:00 EDT in New York is 13:00 UTC; the same instant is 18:30 in Kolkata, outside opening hours.
        ZoneId newYork = ZoneId.of("America/New_York");
        ServiceQueue queue = queue(LocalTime.of(9, 0), LocalTime.of(17, 0), 30);
        Instant nineInNewYork = local(2026, 3, 11, 9, 0, newYork);
        assertThat(BookingRules.validate(queue, newYork, nineInNewYork, NOW, 14).localStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(codeOf(() -> BookingRules.validate(queue, KOLKATA, nineInNewYork, NOW, 14))).isEqualTo(ErrorCode.INVALID_SLOT);
    }

    @Test
    void requiresATime() {
        ServiceQueue queue = queue(LocalTime.of(9, 0), LocalTime.of(17, 0), 15);
        assertThatThrownBy(() -> BookingRules.validate(queue, KOLKATA, null, NOW, 14)).isInstanceOf(ApiException.class);
    }
}
