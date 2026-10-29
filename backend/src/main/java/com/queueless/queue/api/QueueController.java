package com.queueless.queue.api;

import com.queueless.queue.api.QueueDtos.QueueResponse;
import com.queueless.queue.service.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public queue information. */
@Tag(name = "Queues")
@RestController
public class QueueController {

    private final QueueService queues;

    public QueueController(QueueService queues) {
        this.queues = queues;
    }

    @Operation(summary = "Queues of an organization")
    @GetMapping("/api/organizations/{slug}/queues")
    public List<QueueResponse> list(@PathVariable String slug) {
        return queues.listPublic(slug);
    }

    @Operation(summary = "Queue configuration and status")
    @GetMapping("/api/queues/{queueId}")
    public QueueResponse get(@PathVariable UUID queueId) {
        return queues.getPublic(queueId);
    }
}
