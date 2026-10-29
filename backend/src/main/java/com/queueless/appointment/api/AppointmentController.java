package com.queueless.appointment.api;

import com.queueless.appointment.api.AppointmentDtos.AppointmentResponse;
import com.queueless.appointment.api.AppointmentDtos.BookAppointmentRequest;
import com.queueless.appointment.api.AppointmentDtos.SlotsResponse;
import com.queueless.appointment.service.AppointmentService;
import com.queueless.appointment.service.AppointmentService.BookingResult;
import com.queueless.auth.security.AuthenticatedUser;
import com.queueless.ticket.api.TicketDtos.TicketResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Appointments")
@RestController
public class AppointmentController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final AppointmentService appointments;

    public AppointmentController(AppointmentService appointments) {
        this.appointments = appointments;
    }

    @Operation(summary = "Slot availability for one organization-local date (default: today)")
    @GetMapping("/api/queues/{queueId}/slots")
    public SlotsResponse slots(@PathVariable UUID queueId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return appointments.slots(queueId, date);
    }

    @Operation(summary = "Book a slot; returns 201, or 200 when the Idempotency-Key was already used for this booking")
    @PostMapping("/api/queues/{queueId}/appointments")
    public ResponseEntity<AppointmentResponse> book(@PathVariable UUID queueId,
            @Valid @RequestBody BookAppointmentRequest body,
            @Parameter(description = "Client-generated UUID; resending it never creates a second booking")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) UUID idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser user) {
        BookingResult result = appointments.book(queueId, user.getId(), body.scheduledAt(), idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.appointment());
        }
        return ResponseEntity.created(URI.create("/api/appointments/" + result.appointment().id()))
                .body(result.appointment());
    }

    @Operation(summary = "My appointments, newest first")
    @GetMapping("/api/me/appointments")
    public List<AppointmentResponse> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return appointments.listMine(user.getId());
    }

    @Operation(summary = "Appointment details (owner or organization staff)")
    @GetMapping("/api/appointments/{appointmentId}")
    public AppointmentResponse get(@PathVariable UUID appointmentId, @AuthenticationPrincipal AuthenticatedUser user) {
        return appointments.get(appointmentId, user.getId());
    }

    @Operation(summary = "Cancel a booking and release its slot (owner or organization staff); idempotent")
    @PostMapping("/api/appointments/{appointmentId}/cancel")
    public AppointmentResponse cancel(@PathVariable UUID appointmentId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return appointments.cancel(appointmentId, user.getId());
    }

    @Operation(summary = "Check in on arrival: the ticket joins the waiting line (owner or organization staff)")
    @PostMapping("/api/appointments/{appointmentId}/check-in")
    public TicketResponse checkIn(@PathVariable UUID appointmentId, @AuthenticationPrincipal AuthenticatedUser user) {
        return appointments.checkIn(appointmentId, user.getId());
    }
}
