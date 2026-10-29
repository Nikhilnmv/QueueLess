package com.queueless.appointment.api;

import com.queueless.appointment.domain.AppointmentStatus;
import com.queueless.ticket.domain.TicketStatus;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class AppointmentDtos {

    private AppointmentDtos() {}

    /** Only the slot is chosen by the client; status, token and reference are assigned by the server. */
    public record BookAppointmentRequest(@NotNull Instant scheduledAt) {}

    public record AppointmentResponse(
            UUID id,
            String bookingReference,
            AppointmentStatus status,
            UUID queueId,
            String queueName,
            UUID organizationId,
            String organizationName,
            String organizationSlug,
            String timezone,
            Instant scheduledAt,
            LocalDate serviceDate,
            UUID ticketId,
            Integer tokenNumber,
            String displayToken,
            TicketStatus ticketStatus,
            Instant createdAt,
            Instant cancelledAt,
            Instant checkInOpensAt,
            boolean canCancel,
            boolean canCheckIn) {}

    public record SlotResponse(Instant startsAt, LocalTime localTime, int capacity, long booked, long available,
                               boolean bookable) {}

    public record SlotsResponse(UUID queueId, LocalDate date, String timezone, boolean bookingEnabled,
                                int slotDurationMinutes, LocalDate firstBookableDate, LocalDate lastBookableDate,
                                List<SlotResponse> slots) {}
}
