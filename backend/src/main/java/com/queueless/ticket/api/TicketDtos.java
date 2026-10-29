package com.queueless.ticket.api;

import com.queueless.queue.domain.QueueStatus;
import com.queueless.ticket.domain.TicketPriority;
import com.queueless.ticket.domain.TicketSource;
import com.queueless.ticket.domain.TicketStatus;
import com.queueless.ticket.service.WaitTimeEstimator.Basis;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class TicketDtos {

    private TicketDtos() {}

    /** A ticket as seen by its holder (or by staff of its organization). */
    public record TicketResponse(
            UUID id,
            UUID queueId,
            String queueName,
            UUID organizationId,
            String organizationName,
            String organizationSlug,
            String timezone,
            int tokenNumber,
            String displayToken,
            TicketStatus status,
            TicketPriority priority,
            TicketSource source,
            LocalDate serviceDate,
            UUID appointmentId,
            Instant scheduledAt,
            Instant createdAt,
            Instant checkedInAt,
            Instant calledAt,
            Instant serviceStartedAt,
            Instant completedAt,
            Instant noShowAt,
            Instant cancelledAt,
            /** Still BOOKED/WAITING/CALLED after its service date has passed. */
            boolean expired,
            boolean cancellable,
            LiveTicketInfo live) {}

    /** Current position and estimate, computed from the queue state at request time. */
    public record LiveTicketInfo(
            QueueStatus queueStatus,
            Integer position,
            Integer peopleAhead,
            Integer estimatedWaitMinutes,
            String nowServing,
            long waitingCount,
            int averageServiceMinutes,
            Basis estimateBasis,
            long queueVersion) {}

    /** Public, customer-data-free summary of a queue right now. */
    public record QueueLiveResponse(
            UUID queueId,
            QueueStatus status,
            LocalDate serviceDate,
            String timezone,
            NowServing nowServing,
            long waitingCount,
            int estimatedWaitMinutesForNewTicket,
            int averageServiceMinutes,
            Basis estimateBasis,
            int estimateSamples,
            long version) {}

    public record NowServing(int tokenNumber, String displayToken, TicketStatus status) {}

    /** Staff board: who is at the counter, who is waiting (in service order) and who is expected. */
    public record QueueBoardResponse(
            UUID queueId,
            String queueName,
            UUID organizationId,
            QueueStatus status,
            LocalDate serviceDate,
            String timezone,
            long version,
            BoardTicket atCounter,
            List<BoardTicket> waiting,
            List<BoardTicket> expected,
            BoardStats stats,
            String orderingPolicy) {}

    public record BoardTicket(
            UUID ticketId,
            int tokenNumber,
            String displayToken,
            String holderName,
            TicketSource source,
            TicketStatus status,
            TicketPriority priority,
            UUID appointmentId,
            Instant scheduledAt,
            Instant createdAt,
            Instant checkedInAt,
            Instant calledAt,
            Instant serviceStartedAt,
            Integer position,
            Integer estimatedWaitMinutes) {}

    public record BoardStats(long waiting, long expected, long completed, long noShow, long cancelled,
                             int averageServiceMinutes, Basis estimateBasis, int estimateSamples) {}

    public record GuestWalkInRequest(
            @NotBlank @Size(max = 120) String guestName,
            TicketPriority priority) {}

    public record ChangePriorityRequest(@NotNull TicketPriority priority) {}

    public static final String ORDERING_POLICY =
            "Priority first (High, then Normal, then Low); equal priority is served in check-in order; "
                    + "ticket creation time and id break any remaining ties.";
}
