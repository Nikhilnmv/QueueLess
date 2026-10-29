package com.queueless.queue.api;

import com.queueless.auth.security.AuthenticatedUser;
import com.queueless.queue.api.QueueDtos.ChangeQueueStatusRequest;
import com.queueless.queue.api.QueueDtos.QueueConfigRequest;
import com.queueless.queue.api.QueueDtos.QueueResponse;
import com.queueless.queue.service.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Queue configuration (ADMIN) and opening/pausing/closing (STAFF or ADMIN). */
@Tag(name = "Queue management")
@RestController
public class QueueManagementController {

    private final QueueService queues;

    public QueueManagementController(QueueService queues) {
        this.queues = queues;
    }

    @Operation(summary = "All queues of the organization with configuration (STAFF or ADMIN)")
    @GetMapping("/api/admin/organizations/{organizationId}/queues")
    public List<QueueResponse> list(@PathVariable UUID organizationId, @AuthenticationPrincipal AuthenticatedUser user) {
        return queues.listForManagement(organizationId, user.getId());
    }

    @Operation(summary = "Create a queue (ADMIN). New queues start CLOSED.")
    @PostMapping("/api/admin/organizations/{organizationId}/queues")
    public ResponseEntity<QueueResponse> create(@PathVariable UUID organizationId,
            @Valid @RequestBody QueueConfigRequest body, @AuthenticationPrincipal AuthenticatedUser user) {
        QueueResponse queue = queues.create(organizationId, user.getId(), body.toSettings());
        return ResponseEntity.created(URI.create("/api/queues/" + queue.id())).body(queue);
    }

    @Operation(summary = "Update queue configuration (ADMIN)")
    @PutMapping("/api/admin/queues/{queueId}")
    public QueueResponse update(@PathVariable UUID queueId, @Valid @RequestBody QueueConfigRequest body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return queues.update(queueId, user.getId(), body.toSettings());
    }

    @Operation(summary = "Open, pause or close the live queue (STAFF or ADMIN)")
    @PostMapping("/api/staff/queues/{queueId}/status")
    public QueueResponse changeStatus(@PathVariable UUID queueId, @Valid @RequestBody ChangeQueueStatusRequest body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return queues.changeStatus(queueId, user.getId(), body.status());
    }
}
