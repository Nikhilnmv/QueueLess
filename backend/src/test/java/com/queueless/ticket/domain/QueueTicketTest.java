package com.queueless.ticket.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueueTicketTest {

    private static final UUID QUEUE = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    private static final Instant T0 = Instant.parse("2026-03-10T04:30:00Z");

    @Test
    void walkInsJoinTheLineImmediatelyWithNormalPriority() {
        QueueTicket ticket = QueueTicket.walkIn(QUEUE, UUID.randomUUID(), DAY, 7, T0);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.WAITING);
        assertThat(ticket.getCheckedInAt()).isEqualTo(T0);
        assertThat(ticket.getPriority()).isEqualTo(TicketPriority.NORMAL);
        assertThat(ticket.getSource()).isEqualTo(TicketSource.WALK_IN);
    }

    @Test
    void appointmentTicketsWaitForCheckIn() {
        QueueTicket ticket = QueueTicket.forAppointment(QUEUE, UUID.randomUUID(), UUID.randomUUID(), DAY, 1, T0);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.BOOKED);
        assertThat(ticket.getCheckedInAt()).isNull();

        ticket.checkIn(T0.plusSeconds(60));
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.WAITING);
        assertThat(ticket.getCheckedInAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void fullLifecycleRecordsEveryTimestamp() {
        QueueTicket ticket = QueueTicket.guestWalkIn(QUEUE, "Guest", TicketPriority.HIGH, DAY, 3, T0);
        ticket.call(T0.plusSeconds(10));
        ticket.startService(T0.plusSeconds(20));
        ticket.complete(T0.plusSeconds(320));

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.COMPLETED);
        assertThat(ticket.getCalledAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(ticket.getServiceStartedAt()).isEqualTo(T0.plusSeconds(20));
        assertThat(ticket.getCompletedAt()).isEqualTo(T0.plusSeconds(320));
    }

    @Test
    void illegalTransitionsAreRejectedWithAConflictCode() {
        QueueTicket waiting = QueueTicket.walkIn(QUEUE, UUID.randomUUID(), DAY, 1, T0);
        assertThatThrownBy(() -> waiting.complete(T0))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION))
                .hasMessageContaining("waiting");
        assertThatThrownBy(() -> waiting.markNoShow(T0)).isInstanceOf(ApiException.class);
        assertThat(waiting.getStatus()).isEqualTo(TicketStatus.WAITING);

        waiting.call(T0);
        waiting.markNoShow(T0);
        assertThatThrownBy(() -> waiting.cancel(T0)).isInstanceOf(ApiException.class);
    }

    @Test
    void priorityCanOnlyChangeBeforeTheCounter() {
        QueueTicket ticket = QueueTicket.walkIn(QUEUE, UUID.randomUUID(), DAY, 1, T0);
        ticket.changePriority(TicketPriority.HIGH, T0);
        assertThat(ticket.getPriority()).isEqualTo(TicketPriority.HIGH);

        ticket.call(T0);
        assertThatThrownBy(() -> ticket.changePriority(TicketPriority.LOW, T0)).isInstanceOf(ApiException.class);
    }

    @Test
    void priorityRanksMatchTheDatabaseGeneratedColumn() {
        // V2 migration: CASE priority WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END
        assertThat(TicketPriority.HIGH.rank()).isEqualTo(1);
        assertThat(TicketPriority.NORMAL.rank()).isEqualTo(2);
        assertThat(TicketPriority.LOW.rank()).isEqualTo(3);
    }
}
