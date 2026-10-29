package com.queueless.ticket.service;

import com.queueless.common.config.QueueLessProperties;
import com.queueless.queue.domain.ServiceQueue;
import com.queueless.ticket.domain.QueueTicket;
import com.queueless.ticket.domain.QueueTicketRepository;
import com.queueless.ticket.service.WaitTimeEstimator.CounterState;
import com.queueless.ticket.service.WaitTimeEstimator.ServiceTimeStats;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/** Loads the inputs of {@link WaitTimeEstimator} from the database. Runs in the caller's transaction. */
@Component
public class QueueMetrics {

    private final QueueTicketRepository tickets;
    private final QueueLessProperties.Estimation settings;

    public QueueMetrics(QueueTicketRepository tickets, QueueLessProperties properties) {
        this.tickets = tickets;
        this.settings = properties.estimation();
    }

    public ServiceTimeStats serviceTimeStats(ServiceQueue queue) {
        List<Duration> durations = tickets.findRecentServiceIntervals(queue.getId(), Limit.of(settings.sampleSize()))
                .stream()
                .filter(i -> i.startedAt() != null && i.completedAt() != null)
                .map(i -> Duration.between(i.startedAt(), i.completedAt()))
                .toList();
        return WaitTimeEstimator.serviceTimeStats(durations, queue.getDefaultServiceMinutes(), settings.minSamples());
    }

    public Duration remainingAtCounter(Optional<QueueTicket> atCounter, ServiceTimeStats stats, Instant now) {
        CounterState counter = atCounter.map(t -> new CounterState(t.getStatus(), t.getServiceStartedAt())).orElse(null);
        return WaitTimeEstimator.remainingAtCounter(counter, stats, now);
    }
}
