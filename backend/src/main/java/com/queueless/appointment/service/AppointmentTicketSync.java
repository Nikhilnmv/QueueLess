package com.queueless.appointment.service;

import com.queueless.appointment.domain.Appointment;
import com.queueless.appointment.domain.AppointmentRepository;
import com.queueless.common.time.TimeProvider;
import com.queueless.ticket.domain.QueueTicket;
import com.queueless.ticket.domain.TicketStatus;
import com.queueless.ticket.service.TicketLifecycleListener;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Keeps an appointment's status consistent with its ticket, inside the same transaction:
 * check-in, completion, no-show and cancellation of the ticket update the appointment too.
 */
@Component
class AppointmentTicketSync implements TicketLifecycleListener {

    private final AppointmentRepository appointments;
    private final TimeProvider time;

    AppointmentTicketSync(AppointmentRepository appointments, TimeProvider time) {
        this.appointments = appointments;
        this.time = time;
    }

    @Override
    public void onTicketStatusChanged(QueueTicket ticket, TicketStatus previousStatus, UUID actorUserId) {
        if (ticket.getAppointmentId() == null) {
            return;
        }
        Appointment appointment = appointments.findById(ticket.getAppointmentId()).orElseThrow(() ->
                new IllegalStateException("Ticket " + ticket.getId() + " references a missing appointment"));
        Instant now = time.now();
        switch (ticket.getStatus()) {
            case WAITING -> appointment.markCheckedIn(now);
            case COMPLETED -> appointment.markCompleted(now);
            case NO_SHOW -> appointment.markNoShow(now);
            case CANCELLED -> appointment.cancel(now, actorUserId);
            default -> {
                // CALLED and SERVING: the appointment stays CHECKED_IN.
            }
        }
    }
}
