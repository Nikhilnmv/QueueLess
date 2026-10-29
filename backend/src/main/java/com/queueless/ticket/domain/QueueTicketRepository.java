package com.queueless.ticket.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface QueueTicketRepository extends JpaRepository<QueueTicket, UUID> {

    /**
     * Service order: priority (HIGH first), then check-in time, then creation time, then id as a
     * stable tie-breaker. Every query that orders waiting tickets uses this one definition.
     */
    String SERVICE_ORDER = " order by t.priorityRank, t.checkedInAt, t.createdAt, t.id";

    /**
     * Highest token issued so far for the queue and date (0 if none). Only safe to use for
     * allocation while holding the queue lock (see TokenAllocator). Served by uq_tickets_token.
     */
    @Query("select coalesce(max(t.tokenNumber), 0) from QueueTicket t "
            + "where t.queueId = :queueId and t.serviceDate = :serviceDate")
    int findMaxTokenNumber(UUID queueId, LocalDate serviceDate);

    /**
     * Waiting tickets in {@link #SERVICE_ORDER}. This query is the single source of truth for "who
     * is next" (call-next takes the first row) and for queue positions. Served by ix_tickets_waiting_order.
     */
    @Query("""
            select t from QueueTicket t
            where t.queueId = :queueId and t.serviceDate = :serviceDate
              and t.status = com.queueless.ticket.domain.TicketStatus.WAITING""" + SERVICE_ORDER)
    List<QueueTicket> findWaitingInServiceOrder(UUID queueId, LocalDate serviceDate, Limit limit);

    @Query("select count(t) from QueueTicket t where t.queueId = :queueId and t.serviceDate = :serviceDate "
            + "and t.status = com.queueless.ticket.domain.TicketStatus.WAITING")
    long countWaiting(UUID queueId, LocalDate serviceDate);

    @Query("select t from QueueTicket t where t.queueId = :queueId and t.serviceDate = :serviceDate "
            + "and t.status in :statuses")
    List<QueueTicket> findByQueueAndDateAndStatusIn(UUID queueId, LocalDate serviceDate,
            Collection<TicketStatus> statuses);

    /** The ticket currently CALLED or SERVING (at most one, enforced by ux_tickets_active_per_queue_day). */
    default Optional<QueueTicket> findAtCounter(UUID queueId, LocalDate serviceDate) {
        return findByQueueAndDateAndStatusIn(queueId, serviceDate, TicketStatus.AT_COUNTER).stream().findFirst();
    }

    @Query("select t from QueueTicket t where t.queueId = :queueId and t.userId = :userId "
            + "and t.serviceDate = :serviceDate and t.status in :statuses")
    List<QueueTicket> findForUserOnDate(UUID queueId, UUID userId, LocalDate serviceDate,
            Collection<TicketStatus> statuses);

    default Optional<QueueTicket> findActiveForUser(UUID queueId, UUID userId, LocalDate serviceDate) {
        return findForUserOnDate(queueId, userId, serviceDate, TicketStatus.ACTIVE).stream().findFirst();
    }

    Optional<QueueTicket> findByAppointmentId(UUID appointmentId);

    List<QueueTicket> findByAppointmentIdIn(Collection<UUID> appointmentIds);

    record TicketOwnership(UUID ticketId, UUID queueId, UUID userId, UUID organizationId) {}

    /** Who owns the ticket and which organization it belongs to, without loading the entity. */
    @Query("""
            select new com.queueless.ticket.domain.QueueTicketRepository$TicketOwnership(
                t.id, t.queueId, t.userId, q.organizationId)
            from QueueTicket t join ServiceQueue q on q.id = t.queueId
            where t.id = :ticketId""")
    Optional<TicketOwnership> findOwnership(UUID ticketId);

    record ServiceInterval(Instant startedAt, Instant completedAt) {}

    /** Most recent completed services of the queue (for the average service time). */
    @Query("""
            select new com.queueless.ticket.domain.QueueTicketRepository$ServiceInterval(
                t.serviceStartedAt, t.completedAt)
            from QueueTicket t
            where t.queueId = :queueId and t.status = com.queueless.ticket.domain.TicketStatus.COMPLETED
            order by t.completedAt desc""")
    List<ServiceInterval> findRecentServiceIntervals(UUID queueId, Limit limit);

    record StatusCount(TicketStatus status, long count) {}

    @Query("""
            select new com.queueless.ticket.domain.QueueTicketRepository$StatusCount(t.status, count(t))
            from QueueTicket t
            where t.queueId = :queueId and t.serviceDate = :serviceDate
            group by t.status""")
    List<StatusCount> countByStatus(UUID queueId, LocalDate serviceDate);

    /**
     * Staff board read model. Joins users (holder name) and appointments (scheduled time); these
     * tables belong to other modules, but this is a read-only projection - writes always go through
     * the owning module.
     */
    record BoardRow(UUID ticketId, int tokenNumber, TicketStatus status, TicketPriority priority, TicketSource source,
                    UUID userId, String holderName, UUID appointmentId, Instant scheduledAt, Instant createdAt,
                    Instant checkedInAt, Instant calledAt, Instant serviceStartedAt) {}

    @Query("""
            select new com.queueless.ticket.domain.QueueTicketRepository$BoardRow(
                t.id, t.tokenNumber, t.status, t.priority, t.source, t.userId, coalesce(u.fullName, t.guestName),
                t.appointmentId, a.scheduledAt, t.createdAt, t.checkedInAt, t.calledAt, t.serviceStartedAt)
            from QueueTicket t
                left join User u on u.id = t.userId
                left join Appointment a on a.id = t.appointmentId
            where t.queueId = :queueId and t.serviceDate = :serviceDate and t.status in :statuses""" + SERVICE_ORDER)
    List<BoardRow> findBoardRows(UUID queueId, LocalDate serviceDate, Collection<TicketStatus> statuses);

    @Query("""
            select new com.queueless.ticket.domain.QueueTicketRepository$BoardRow(
                t.id, t.tokenNumber, t.status, t.priority, t.source, t.userId, coalesce(u.fullName, t.guestName),
                t.appointmentId, a.scheduledAt, t.createdAt, t.checkedInAt, t.calledAt, t.serviceStartedAt)
            from QueueTicket t
                left join User u on u.id = t.userId
                left join Appointment a on a.id = t.appointmentId
            where t.id = :ticketId""")
    Optional<BoardRow> findBoardRow(UUID ticketId);

    @Query("select a.scheduledAt from Appointment a where a.id = :appointmentId")
    Optional<Instant> findAppointmentScheduledAt(UUID appointmentId);

    /** Ticket ids of a user, newest first (served by ix_tickets_user_date). */
    @Query("select t.id from QueueTicket t where t.userId = :userId order by t.serviceDate desc, t.createdAt desc")
    List<UUID> findIdsOfUser(UUID userId, Limit limit);
}
