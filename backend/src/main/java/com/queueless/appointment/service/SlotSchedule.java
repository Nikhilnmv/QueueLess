package com.queueless.appointment.service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Appointment slot grid of a queue: slots start at the opening time and repeat every
 * {@code slotMinutes}; the last slot must end no later than the closing time.
 * Works in minutes of the day, so it never wraps around midnight.
 */
public final class SlotSchedule {

    private SlotSchedule() {}

    public static List<LocalTime> slotStarts(LocalTime opening, LocalTime closing, int slotMinutes) {
        int open = minuteOfDay(opening);
        int close = minuteOfDay(closing);
        List<LocalTime> starts = new ArrayList<>();
        for (int minute = open; minute + slotMinutes <= close; minute += slotMinutes) {
            starts.add(LocalTime.of(minute / 60, minute % 60));
        }
        return starts;
    }

    public static boolean isSlotStart(LocalTime time, LocalTime opening, LocalTime closing, int slotMinutes) {
        if (time.getSecond() != 0 || time.getNano() != 0) {
            return false;
        }
        int minute = minuteOfDay(time);
        int open = minuteOfDay(opening);
        return minute >= open && minute + slotMinutes <= minuteOfDay(closing) && (minute - open) % slotMinutes == 0;
    }

    private static int minuteOfDay(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
