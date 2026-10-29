package com.queueless.ticket.api;

import com.queueless.auth.security.AuthenticatedUser;
import com.queueless.ticket.api.TicketDtos.BoardTicket;
import com.queueless.ticket.api.TicketDtos.ChangePriorityRequest;
import com.queueless.ticket.api.TicketDtos.GuestWalkInRequest;
import com.queueless.ticket.api.TicketDtos.QueueBoardResponse;
import com.queueless.ticket.service.QueueOperationsService;
import com.queueless.ticket.service.QueueReadService;
import com.queueless.ticket.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff operations. Every call is authorized against the organization that owns the queue/ticket. */
@Tag(name = "Staff queue operations")
@RestController
@RequestMapping("/api/staff")
public class StaffQueueController {

    private final QueueOperationsService operations;
    private final TicketService tickets;
    private final QueueReadService reads;

    public StaffQueueController(QueueOperationsService operations, TicketService tickets, QueueReadService reads) {
        this.operations = operations;
        this.tickets = tickets;
        this.reads = reads;
    }

    @Operation(summary = "Live board: ticket at the counter, waiting line in service order, expected arrivals")
    @GetMapping("/queues/{queueId}/board")
    public QueueBoardResponse board(@PathVariable UUID queueId, @AuthenticationPrincipal AuthenticatedUser user) {
        return reads.board(queueId, user.getId());
    }

    @Operation(summary = "Call the next waiting ticket (queue must be OPEN and the counter free)")
    @PostMapping("/queues/{queueId}/call-next")
    public BoardTicket callNext(@PathVariable UUID queueId, @AuthenticationPrincipal AuthenticatedUser user) {
        return operations.callNext(queueId, user.getId());
    }

    @Operation(summary = "Register a walk-in for a person without an account")
    @PostMapping("/queues/{queueId}/walk-ins")
    public ResponseEntity<BoardTicket> registerGuest(@PathVariable UUID queueId,
            @Valid @RequestBody GuestWalkInRequest body, @AuthenticationPrincipal AuthenticatedUser user) {
        BoardTicket ticket = tickets.registerGuest(queueId, user.getId(), body.guestName(), body.priority());
        return ResponseEntity.created(URI.create("/api/tickets/" + ticket.ticketId())).body(ticket);
    }

    @Operation(summary = "CALLED -> SERVING")
    @PostMapping("/tickets/{ticketId}/start")
    public BoardTicket start(@PathVariable UUID ticketId, @AuthenticationPrincipal AuthenticatedUser user) {
        return operations.startService(ticketId, user.getId());
    }

    @Operation(summary = "SERVING -> COMPLETED")
    @PostMapping("/tickets/{ticketId}/complete")
    public BoardTicket complete(@PathVariable UUID ticketId, @AuthenticationPrincipal AuthenticatedUser user) {
        return operations.complete(ticketId, user.getId());
    }

    @Operation(summary = "CALLED -> NO_SHOW")
    @PostMapping("/tickets/{ticketId}/no-show")
    public BoardTicket noShow(@PathVariable UUID ticketId, @AuthenticationPrincipal AuthenticatedUser user) {
        return operations.markNoShow(ticketId, user.getId());
    }

    @Operation(summary = "Change the priority of a BOOKED or WAITING ticket")
    @PatchMapping("/tickets/{ticketId}/priority")
    public BoardTicket changePriority(@PathVariable UUID ticketId, @Valid @RequestBody ChangePriorityRequest body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return operations.changePriority(ticketId, user.getId(), body.priority());
    }
}
