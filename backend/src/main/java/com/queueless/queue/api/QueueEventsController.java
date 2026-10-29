package com.queueless.queue.api;

import com.queueless.auth.security.AuthenticatedUser;
import com.queueless.queue.api.QueueDtos.StreamReady;
import com.queueless.queue.service.QueueService;
import com.queueless.queue.service.QueueService.QueueContext;
import com.queueless.realtime.SseEmitterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events stream of queue changes. Requires a logged-in user and an existing queue in an
 * active organization. Events carry only {queueId, version, reason}: subscribers re-fetch the data
 * they are authorized to see (public summary, their own ticket, or the staff board).
 */
@Tag(name = "Real time")
@RestController
public class QueueEventsController {

    private final QueueService queues;
    private final SseEmitterRegistry registry;

    public QueueEventsController(QueueService queues, SseEmitterRegistry registry) {
        this.queues = queues;
        this.registry = registry;
    }

    @Operation(summary = "Subscribe to queue-change events (text/event-stream)")
    @GetMapping(path = "/api/queues/{queueId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable UUID queueId, @AuthenticationPrincipal AuthenticatedUser user,
            HttpServletResponse response) {
        QueueContext context = queues.requireActiveQueue(queueId);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return registry.subscribeToQueue(queueId, user.getId(),
                new StreamReady(queueId, context.queue().getStateVersion()));
    }
}
