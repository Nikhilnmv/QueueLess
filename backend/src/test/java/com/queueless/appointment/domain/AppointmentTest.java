package com.queueless.appointment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queueless.common.error.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppointmentTest {

    private static final Instant T0 = Instant.parse("2026-03-10T04:30:00Z");

    private static Appointment booked() {
        return new Appointment(UUID.randomUUID(), UUID.randomUUID(), T0.plusSeconds(3600), LocalDate.of(2026, 3, 10),
                "QL-ABC123", null, T0);
    }

    @Test
    void followsTheTicketLifecycle() {
        Appointment appointment = booked();
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.BOOKED);
        appointment.markCheckedIn(T0);
        appointment.markCompleted(T0);
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(appointment.getStatus().consumesCapacity()).isTrue();
    }

    @Test
    void cancellationIsIdempotentAndReleasesCapacity() {
        Appointment appointment = booked();
        UUID actor = UUID.randomUUID();
        appointment.cancel(T0, actor);
        appointment.cancel(T0.plusSeconds(5), UUID.randomUUID()); // repeated: no change
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(appointment.getCancelledAt()).isEqualTo(T0);
        assertThat(appointment.getCancelledByUserId()).isEqualTo(actor);
        assertThat(appointment.getStatus().consumesCapacity()).isFalse();
    }

    @Test
    void finishedAppointmentsCannotBeCancelledOrSkipSteps() {
        Appointment appointment = booked();
        assertThatThrownBy(() -> appointment.markCompleted(T0)).isInstanceOf(ApiException.class);
        appointment.markCheckedIn(T0);
        appointment.markNoShow(T0);
        assertThatThrownBy(() -> appointment.cancel(T0, UUID.randomUUID())).isInstanceOf(ApiException.class);
    }
}
