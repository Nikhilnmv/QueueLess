package com.queueless.ticket.service;

import com.queueless.common.time.TimeProvider;
import com.queueless.organization.domain.Organization;
import com.queueless.queue.domain.ServiceQueue;
import com.queueless.ticket.api.TicketDtos.BoardTicket;
import com.queueless.ticket.api.TicketDtos.LiveTicketInfo;
import com.queueless.ticket.api.TicketDtos.TicketResponse;
import com.queueless.ticket.domain.QueueTicket;
import com.queueless.ticket.domain.QueueTicketRepository;
import com.queueless.ticket.domain.QueueTicketRepository.BoardRow;
import com.queueless.ticket.domain.TicketStatus;
import com.queueless.ticket.service.WaitTimeEstimator.ServiceTimeStats;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Builds API views of tickets, including live position and wait estimate. Always runs inside the
 * caller's transaction so the numbers come from one consistent view of the queue.
 */
@Component
public class TicketViews {

    private final QueueTicketRepository tickets;
    private final QueueMetrics metrics;
    private final TimeProvider time;

    public TicketViews(QueueTicketRepository tickets, QueueMetrics metrics, TimeProvider time) {
        this.tickets = tickets;
        this.metrics = metrics;
        this.time = time;
    }

    public TicketResponse describe(QueueTicket ticket, ServiceQueue queue, Organization organization) {
        Instant now = time.now();
        LocalDate today = LocalDate.ofInstant(now, organization.zoneId());
        boolean expired = ticket.getStatus().isActive() && ticket.getServiceDate().isBefore(today);
        LiveTicketInfo live = !expired && ticket.getStatus().isActive() && ticket.getServiceDate().equals(today)
                ? liveInfo(ticket, queue, today, now)
                : null;
        Instant scheduledAt = ticket.getAppointmentId() == null ? null
                : tickets.findAppointmentScheduledAt(ticket.getAppointmentId()).orElse(null);
        boolean cancellable = !expired && ticket.getStatus().canTransitionTo(TicketStatus.CANCELLED);
        return new TicketResponse(ticket.getId(), queue.getId(), queue.getName(), organization.getId(),
                organization.getName(), organization.getSlug(), organization.getTimezone(), ticket.getTokenNumber(),
                queue.displayToken(ticket.getTokenNumber()), ticket.getStatus(), ticket.getPriority(),
                ticket.getSource(), ticket.getServiceDate(), ticket.getAppointmentId(), scheduledAt,
                ticket.getCreatedAt(), ticket.getCheckedInAt(), ticket.getCalledAt(), ticket.getServiceStartedAt(),
                ticket.getCompletedAt(), ticket.getNoShowAt(), ticket.getCancelledAt(), expired, cancellable, live);
    }

    private LiveTicketInfo liveInfo(QueueTicket ticket, ServiceQueue queue, LocalDate today, Instant now) {
        List<QueueTicket> waiting = tickets.findWaitingInServiceOrder(queue.getId(), today, Limit.unlimited());
        Optional<QueueTicket> atCounter = tickets.findAtCounter(queue.getId(), today);
        ServiceTimeStats stats = metrics.serviceTimeStats(queue);
        Duration remaining = metrics.remainingAtCounter(atCounter, stats, now);

        Integer position = null;
        Integer peopleAhead = null;
        Integer estimate = null;
        if (ticket.getStatus() == TicketStatus.WAITING) {
            int index = indexOf(waiting, ticket.getId());
            if (index >= 0) {
                position = index + 1;
                peopleAhead = index;
                estimate = WaitTimeEstimator.estimateWaitMinutes(index, remaining, stats);
            }
        } else if (TicketStatus.AT_COUNTER.contains(ticket.getStatus())) {
            position = 0;
            peopleAhead = 0;
            estimate = 0;
        }
        String nowServing = atCounter.map(t -> queue.displayToken(t.getTokenNumber())).orElse(null);
        return new LiveTicketInfo(queue.getStatus(), position, peopleAhead, estimate, nowServing, waiting.size(),
                stats.averageMinutesRounded(), stats.basis(), queue.getStateVersion());
    }

    public BoardTicket boardTicket(BoardRow row, ServiceQueue queue, Integer position, Integer estimate) {
        return new BoardTicket(row.ticketId(), row.tokenNumber(), queue.displayToken(row.tokenNumber()),
                row.holderName(), row.source(), row.status(), row.priority(), row.appointmentId(), row.scheduledAt(),
                row.createdAt(), row.checkedInAt(), row.calledAt(), row.serviceStartedAt(), position, estimate);
    }

    private static int indexOf(List<QueueTicket> waiting, UUID ticketId) {
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId().equals(ticketId)) {
                return i;
            }
        }
        return -1;
    }
}
