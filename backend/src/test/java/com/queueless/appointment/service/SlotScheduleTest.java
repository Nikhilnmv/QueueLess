package com.queueless.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlotScheduleTest {

    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);

    @Test
    void slotsRepeatFromOpeningAndTheLastOneEndsByClosing() {
        List<LocalTime> slots = SlotSchedule.slotStarts(NINE, FIVE_PM, 15);
        assertThat(slots).hasSize(32);
        assertThat(slots.get(0)).isEqualTo(NINE);
        assertThat(slots.get(31)).isEqualTo(LocalTime.of(16, 45));
    }

    @Test
    void aPartialSlotAtTheEndIsNotOffered() {
        // 09:00-10:50 with 20-minute slots: the 10:40 slot would end at 11:00, after closing.
        assertThat(SlotSchedule.slotStarts(NINE, LocalTime.of(10, 50), 20))
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 20), LocalTime.of(9, 40),
                        LocalTime.of(10, 0), LocalTime.of(10, 20));
    }

    @Test
    void lateClosingNeverWrapsAroundMidnight() {
        List<LocalTime> slots = SlotSchedule.slotStarts(LocalTime.of(22, 0), LocalTime.of(23, 59), 30);
        assertThat(slots).containsExactly(LocalTime.of(22, 0), LocalTime.of(22, 30), LocalTime.of(23, 0));
    }

    @Test
    void onlyGridAlignedTimesInsideOpeningHoursAreSlotStarts() {
        assertThat(SlotSchedule.isSlotStart(LocalTime.of(10, 30), NINE, FIVE_PM, 15)).isTrue();
        assertThat(SlotSchedule.isSlotStart(LocalTime.of(10, 7), NINE, FIVE_PM, 15)).isFalse();
        assertThat(SlotSchedule.isSlotStart(LocalTime.of(8, 45), NINE, FIVE_PM, 15)).isFalse();
        assertThat(SlotSchedule.isSlotStart(LocalTime.of(16, 45), NINE, FIVE_PM, 15)).isTrue();
        assertThat(SlotSchedule.isSlotStart(FIVE_PM, NINE, FIVE_PM, 15)).isFalse();
        assertThat(SlotSchedule.isSlotStart(LocalTime.of(10, 30, 1), NINE, FIVE_PM, 15)).isFalse();
    }
}
