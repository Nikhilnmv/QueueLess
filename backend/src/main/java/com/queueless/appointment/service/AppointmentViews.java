package com.queueless.appointment.service;

import com.queueless.appointment.api.AppointmentDtos.AppointmentResponse;
import com.queueless.appointment.domain.Appointment;
import com.queueless.appointment.domain.AppointmentStatus;
import com.queueless.common.config.QueueLessProperties;
import com.queueless.common.time.TimeProvider;
import com.queueless.organization.domain.Organization;
import com.queueless.queue.domain.ServiceQueue;
import com.queueless.ticket.domain.QueueTicket;
import com.queueless.ticket.domain.TicketStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** Builds appointment views, including server-computed flags that tell the UI which actions apply. */
@Component
public class AppointmentViews {

    private final TimeProvider time;
    private final Duration earlyCheckIn;

    public AppointmentViews(TimeProvider time, QueueLessProperties properties) {
        this.time = time;
        this.earlyCheckIn = Duration.ofMinutes(properties.checkIn().earlyMinutes());
    }

    public Instant checkInOpensAt(Appointment appointment) {
        return appointment.getScheduledAt().minus(earlyCheckIn);
    }

    public AppointmentResponse describe(Appointment appointment, QueueTicket ticket, ServiceQueue queue,
            Organization organization) {
        Instant now = time.now();
        LocalDate today = LocalDate.ofInstant(now, organization.zoneId());
        Instant opensAt = checkInOpensAt(appointment);
        boolean canCheckIn = appointment.getStatus() == AppointmentStatus.BOOKED
                && appointment.getServiceDate().equals(today)
                && !now.isBefore(opensAt)
                && queue.acceptsNewArrivals();
        boolean canCancel = appointment.getStatus().isActive() && ticket != null
                && ticket.getStatus().canTransitionTo(TicketStatus.CANCELLED)
                && !appointment.getServiceDate().isBefore(today);
        return new AppointmentResponse(appointment.getId(), appointment.getBookingReference(), appointment.getStatus(),
                queue.getId(), queue.getName(), organization.getId(), organization.getName(), organization.getSlug(),
                organization.getTimezone(), appointment.getScheduledAt(), appointment.getServiceDate(),
                ticket == null ? null : ticket.getId(),
                ticket == null ? null : ticket.getTokenNumber(),
                ticket == null ? null : queue.displayToken(ticket.getTokenNumber()),
                ticket == null ? null : ticket.getStatus(),
                appointment.getCreatedAt(), appointment.getCancelledAt(), opensAt, canCancel, canCheckIn);
    }
}
