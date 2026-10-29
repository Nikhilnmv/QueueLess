package com.queueless.ticket.service;

import com.queueless.common.error.ApiException;
import com.queueless.common.time.TimeProvider;
import com.queueless.membership.domain.Permission;
import com.queueless.membership.service.AccessControlService;
import com.queueless.organization.domain.Organization;
import com.queueless.organization.domain.OrganizationRepository;
import com.queueless.queue.domain.ServiceQueue;
import com.queueless.queue.domain.ServiceQueueRepository;
import com.queueless.queue.service.QueueService;
import com.queueless.queue.service.QueueService.QueueContext;
import com.queueless.ticket.api.TicketDtos;
import com.queueless.ticket.api.TicketDtos.BoardStats;
import com.queueless.ticket.api.TicketDtos.BoardTicket;
import com.queueless.ticket.api.TicketDtos.NowServing;
import com.queueless.ticket.api.TicketDtos.QueueBoardResponse;
import com.queueless.ticket.api.TicketDtos.QueueLiveResponse;
import com.queueless.ticket.api.TicketDtos.TicketResponse;
import com.queueless.ticket.domain.QueueTicket;
import com.queueless.ticket.domain.QueueTicketRepository;
import com.queueless.ticket.domain.QueueTicketRepository.BoardRow;
import com.queueless.ticket.domain.QueueTicketRepository.StatusCount;
import com.queueless.ticket.domain.QueueTicketRepository.TicketOwnership;
import com.queueless.ticket.domain.TicketStatus;
import com.queueless.ticket.service.WaitTimeEstimator.CounterState;
import com.queueless.ticket.service.WaitTimeEstimator.Position;
import com.queueless.ticket.service.WaitTimeEstimator.ServiceTimeStats;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the live queue. Each method runs in a read-only REPEATABLE READ transaction: all of
 * its queries see the same database snapshot, so a call-next committing halfway through cannot
 * produce a view where a ticket is both waiting and at the counter.
 */
@Service
public class QueueReadService {

    private static final int MY_TICKETS_LIMIT = 30;

    private final QueueTicketRepository tickets;
    private final QueueService queues;
    private final ServiceQueueRepository queueRepository;
    private final OrganizationRepository organizationRepository;
    private final AccessControlService access;
    private final QueueMetrics metrics;
    private final TicketViews views;
    private final TimeProvider time;

    public QueueReadService(QueueTicketRepository tickets, QueueService queues, ServiceQueueRepository queueRepository,
            OrganizationRepository organizationRepository, AccessControlService access, QueueMetrics metrics,
            TicketViews views, TimeProvider time) {
        this.tickets = tickets;
        this.queues = queues;
        this.queueRepository = queueRepository;
        this.organizationRepository = organizationRepository;
        this.access = access;
        this.metrics = metrics;
        this.views = views;
        this.time = time;
    }

    /** Public "now serving / people waiting / estimated wait" summary. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QueueLiveResponse live(UUID queueId) {
        QueueContext context = queues.requireActiveQueue(queueId);
        ServiceQueue queue = context.queue();
        Instant now = time.now();
        LocalDate today = LocalDate.ofInstant(now, context.organization().zoneId());

        Optional<QueueTicket> atCounter = tickets.findAtCounter(queueId, today);
        long waiting = tickets.countWaiting(queueId, today);
        ServiceTimeStats stats = metrics.serviceTimeStats(queue);
        Duration remaining = metrics.remainingAtCounter(atCounter, stats, now);
        NowServing nowServing = atCounter
                .map(t -> new NowServing(t.getTokenNumber(), queue.displayToken(t.getTokenNumber()), t.getStatus()))
                .orElse(null);
        return new QueueLiveResponse(queueId, queue.getStatus(), today, context.organization().getTimezone(),
                nowServing, waiting, WaitTimeEstimator.estimateWaitMinutes((int) waiting, remaining, stats),
                stats.averageMinutesRounded(), stats.basis(), stats.samples(), queue.getStateVersion());
    }

    /** Staff board with customer names; requires STAFF or ADMIN of the queue's organization. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QueueBoardResponse board(UUID queueId, UUID staffId) {
        QueueContext context = queues.requireActiveQueue(queueId);
        access.require(staffId, context.organization().getId(), Permission.VIEW_QUEUE_BOARD);
        ServiceQueue queue = context.queue();
        Instant now = time.now();
        LocalDate today = LocalDate.ofInstant(now, context.organization().zoneId());

        List<BoardRow> rows = tickets.findBoardRows(queueId, today, TicketStatus.ACTIVE);
        BoardRow counterRow = rows.stream().filter(r -> TicketStatus.AT_COUNTER.contains(r.status())).findFirst()
                .orElse(null);
        List<BoardRow> waitingRows = rows.stream().filter(r -> r.status() == TicketStatus.WAITING).toList();
        List<BoardRow> expectedRows = rows.stream().filter(r -> r.status() == TicketStatus.BOOKED)
                .sorted(Comparator.comparing(BoardRow::scheduledAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(BoardRow::tokenNumber))
                .toList();

        ServiceTimeStats stats = metrics.serviceTimeStats(queue);
        Duration remaining = counterRow == null ? Duration.ZERO : WaitTimeEstimator.remainingAtCounter(
                new CounterState(counterRow.status(), counterRow.serviceStartedAt()), stats, now);
        List<Position> positions = WaitTimeEstimator.positions(waitingRows.size(), remaining, stats);
        List<BoardTicket> waiting = new ArrayList<>(waitingRows.size());
        for (int i = 0; i < waitingRows.size(); i++) {
            Position position = positions.get(i);
            waiting.add(views.boardTicket(waitingRows.get(i), queue, position.position(), position.estimatedWaitMinutes()));
        }
        List<BoardTicket> expected = expectedRows.stream().map(r -> views.boardTicket(r, queue, null, null)).toList();

        Map<TicketStatus, Long> counts = new EnumMap<>(TicketStatus.class);
        for (StatusCount count : tickets.countByStatus(queueId, today)) {
            counts.put(count.status(), count.count());
        }
        BoardStats boardStats = new BoardStats(waiting.size(), expected.size(),
                counts.getOrDefault(TicketStatus.COMPLETED, 0L), counts.getOrDefault(TicketStatus.NO_SHOW, 0L),
                counts.getOrDefault(TicketStatus.CANCELLED, 0L), stats.averageMinutesRounded(), stats.basis(),
                stats.samples());

        return new QueueBoardResponse(queueId, queue.getName(), context.organization().getId(), queue.getStatus(),
                today, context.organization().getTimezone(), queue.getStateVersion(),
                counterRow == null ? null : views.boardTicket(counterRow, queue, 0, 0), waiting, expected, boardStats,
                TicketDtos.ORDERING_POLICY);
    }

    /** A ticket with live position; visible to its holder and to staff of its organization only. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TicketResponse ticket(UUID ticketId, UUID actorId) {
        TicketOwnership ownership = tickets.findOwnership(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        boolean owner = actorId.equals(ownership.userId());
        if (!owner && !access.has(actorId, ownership.organizationId(), Permission.VIEW_QUEUE_BOARD)) {
            throw ApiException.notFound("Ticket");
        }
        QueueContext context = queues.requireActiveQueue(ownership.queueId());
        QueueTicket ticket = tickets.findById(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        return views.describe(ticket, context.queue(), context.organization());
    }

    /** The current user's most recent tickets, newest service date first. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TicketResponse> myTickets(UUID userId) {
        List<UUID> ids = tickets.findIdsOfUser(userId, Limit.of(MY_TICKETS_LIMIT));
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, QueueTicket> byId = tickets.findAllById(ids).stream()
                .collect(Collectors.toMap(QueueTicket::getId, Function.identity()));
        List<UUID> queueIds = byId.values().stream().map(QueueTicket::getQueueId).distinct().toList();
        Map<UUID, ServiceQueue> queuesById = queueRepository.findAllById(queueIds).stream()
                .collect(Collectors.toMap(ServiceQueue::getId, Function.identity()));
        List<UUID> organizationIds = queuesById.values().stream().map(ServiceQueue::getOrganizationId).distinct().toList();
        Map<UUID, Organization> organizationsById = organizationRepository.findAllById(organizationIds).stream()
                .filter(Organization::isActive)
                .collect(Collectors.toMap(Organization::getId, Function.identity()));

        List<TicketResponse> result = new ArrayList<>();
        for (UUID id : ids) {
            QueueTicket ticket = byId.get(id);
            ServiceQueue queue = queuesById.get(ticket.getQueueId());
            Organization organization = organizationsById.get(queue.getOrganizationId());
            if (organization != null) {
                result.add(views.describe(ticket, queue, organization));
            }
        }
        return result;
    }
}
