package com.queueless.ticket.api;

import com.queueless.auth.security.AuthenticatedUser;
import com.queueless.ticket.api.TicketDtos.QueueLiveResponse;
import com.queueless.ticket.api.TicketDtos.TicketResponse;
import com.queueless.ticket.service.QueueReadService;
import com.queueless.ticket.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer-facing ticket endpoints and the public live queue summary. */
@Tag(name = "Tickets")
@RestController
public class TicketController {

    private final TicketService tickets;
    private final QueueReadService reads;

    public TicketController(TicketService tickets, QueueReadService reads) {
        this.tickets = tickets;
        this.reads = reads;
    }

    @Operation(summary = "Public live summary: now serving, people waiting, estimated wait")
    @GetMapping("/api/queues/{queueId}/live")
    public QueueLiveResponse live(@PathVariable UUID queueId) {
        return reads.live(queueId);
    }

    @Operation(summary = "Join today's queue as a walk-in and receive a token")
    @PostMapping("/api/queues/{queueId}/tickets")
    public ResponseEntity<TicketResponse> join(@PathVariable UUID queueId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        TicketResponse ticket = tickets.joinQueue(queueId, user.getId());
        return ResponseEntity.created(URI.create("/api/tickets/" + ticket.id())).body(ticket);
    }

    @Operation(summary = "My recent tickets with live position for today's active ones")
    @GetMapping("/api/me/tickets")
    public List<TicketResponse> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return reads.myTickets(user.getId());
    }

    @Operation(summary = "Ticket details with live position (holder or organization staff)")
    @GetMapping("/api/tickets/{ticketId}")
    public TicketResponse get(@PathVariable UUID ticketId, @AuthenticationPrincipal AuthenticatedUser user) {
        return reads.ticket(ticketId, user.getId());
    }

    @Operation(summary = "Cancel a BOOKED or WAITING ticket (holder or organization staff); idempotent")
    @PostMapping("/api/tickets/{ticketId}/cancel")
    public TicketResponse cancel(@PathVariable UUID ticketId, @AuthenticationPrincipal AuthenticatedUser user) {
        return tickets.cancel(ticketId, user.getId());
    }
}
