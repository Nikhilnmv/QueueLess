package com.queueless.ticket.service;

import com.queueless.ticket.domain.TicketStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure waiting-time arithmetic (no database access), so it is fully unit-testable.
 *
 * <p>Model: one counter serves one ticket at a time. The wait of a customer with {@code k} people
 * ahead is the time left for the ticket at the counter plus {@code k} average services:
 * <pre>  wait = remaining(active) + k * averageServiceTime</pre>
 * The average comes from recently completed services; until enough history exists the queue's
 * configured default service time is used. Results are estimates, never guarantees.
 */
public final class WaitTimeEstimator {

    private WaitTimeEstimator() {}

    public enum Basis {
        /** Average of recently completed services. */
        HISTORY,
        /** Not enough history yet: the queue's configured default service time. */
        DEFAULT
    }

    public record ServiceTimeStats(Duration average, int samples, Basis basis) {

        public int averageMinutesRounded() {
            return (int) Math.max(1, Math.round(average.toSeconds() / 60.0));
        }
    }

    /** Snapshot of the ticket at the counter (CALLED or SERVING). */
    public record CounterState(TicketStatus status, Instant serviceStartedAt) {}

    public record Position(int position, int peopleAhead, int estimatedWaitMinutes) {}

    /**
     * Mean of recent service durations. Each duration is clamped to [1 minute, 3 x default] so a
     * ticket completed by mistake after a few seconds, or left open for hours, does not distort it.
     */
    public static ServiceTimeStats serviceTimeStats(List<Duration> recentDurations, int defaultMinutes,
            int minSamples) {
        Duration fallback = Duration.ofMinutes(defaultMinutes);
        List<Duration> valid = recentDurations.stream().filter(d -> d != null && !d.isNegative()).toList();
        if (valid.size() < minSamples) {
            return new ServiceTimeStats(fallback, valid.size(), Basis.DEFAULT);
        }
        long minSeconds = 60;
        long maxSeconds = fallback.multipliedBy(3).toSeconds();
        long total = 0;
        for (Duration duration : valid) {
            total += Math.min(maxSeconds, Math.max(minSeconds, duration.toSeconds()));
        }
        return new ServiceTimeStats(Duration.ofSeconds(Math.round((double) total / valid.size())), valid.size(),
                Basis.HISTORY);
    }

    /**
     * Time until the counter is free. SERVING: the unfinished part of an average service (never
     * negative). CALLED: a full average service, because service has not started. Empty counter: zero.
     */
    public static Duration remainingAtCounter(CounterState counter, ServiceTimeStats stats, Instant now) {
        if (counter == null) {
            return Duration.ZERO;
        }
        if (counter.status() == TicketStatus.SERVING && counter.serviceStartedAt() != null) {
            Duration remaining = stats.average().minus(Duration.between(counter.serviceStartedAt(), now));
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }
        return stats.average();
    }

    /** Estimated wait in whole minutes (rounded up) for someone with {@code peopleAhead} tickets in front. */
    public static int estimateWaitMinutes(int peopleAhead, Duration remainingAtCounter, ServiceTimeStats stats) {
        if (peopleAhead < 0) {
            throw new IllegalArgumentException("peopleAhead must not be negative");
        }
        long seconds = remainingAtCounter.plus(stats.average().multipliedBy(peopleAhead)).toSeconds();
        return (int) ((seconds + 59) / 60);
    }

    /**
     * Positions for a waiting line that is already in service order: the first ticket is position 1
     * (next to be called) with nobody ahead.
     */
    public static List<Position> positions(int waitingCount, Duration remainingAtCounter, ServiceTimeStats stats) {
        List<Position> positions = new ArrayList<>(waitingCount);
        for (int index = 0; index < waitingCount; index++) {
            positions.add(new Position(index + 1, index, estimateWaitMinutes(index, remainingAtCounter, stats)));
        }
        return positions;
    }
}
