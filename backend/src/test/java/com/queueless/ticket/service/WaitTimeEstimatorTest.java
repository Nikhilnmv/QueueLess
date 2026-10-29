package com.queueless.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queueless.ticket.domain.TicketStatus;
import com.queueless.ticket.service.WaitTimeEstimator.Basis;
import com.queueless.ticket.service.WaitTimeEstimator.CounterState;
import com.queueless.ticket.service.WaitTimeEstimator.Position;
import com.queueless.ticket.service.WaitTimeEstimator.ServiceTimeStats;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaitTimeEstimatorTest {

    private static final Instant NOW = Instant.parse("2026-03-10T05:00:00Z");
    private static final ServiceTimeStats TEN_MINUTES = new ServiceTimeStats(Duration.ofMinutes(10), 0, Basis.DEFAULT);

    @Test
    void fallsBackToTheConfiguredDefaultWithoutEnoughHistory() {
        ServiceTimeStats stats = WaitTimeEstimator.serviceTimeStats(
                List.of(Duration.ofMinutes(2), Duration.ofMinutes(4)), 10, 3);
        assertThat(stats.basis()).isEqualTo(Basis.DEFAULT);
        assertThat(stats.average()).isEqualTo(Duration.ofMinutes(10));
        assertThat(stats.samples()).isEqualTo(2);
    }

    @Test
    void averagesRecentServicesOnceEnoughHistoryExists() {
        ServiceTimeStats stats = WaitTimeEstimator.serviceTimeStats(
                List.of(Duration.ofMinutes(4), Duration.ofMinutes(6), Duration.ofMinutes(8)), 10, 3);
        assertThat(stats.basis()).isEqualTo(Basis.HISTORY);
        assertThat(stats.average()).isEqualTo(Duration.ofMinutes(6));
        assertThat(stats.averageMinutesRounded()).isEqualTo(6);
    }

    @Test
    void outliersAreClampedSoTheyCannotDistortTheAverage() {
        // 5 seconds counts as 1 minute; 4 hours counts as 3 x default = 30 minutes.
        ServiceTimeStats stats = WaitTimeEstimator.serviceTimeStats(
                List.of(Duration.ofSeconds(5), Duration.ofHours(4), Duration.ofMinutes(11)), 10, 3);
        assertThat(stats.average()).isEqualTo(Duration.ofMinutes(14)); // (1 + 30 + 11) / 3
    }

    @Test
    void invalidDurationsAreIgnored() {
        ServiceTimeStats stats = WaitTimeEstimator.serviceTimeStats(
                List.of(Duration.ofMinutes(-3), Duration.ofMinutes(5)), 10, 2);
        assertThat(stats.basis()).isEqualTo(Basis.DEFAULT);
    }

    @Test
    void remainingTimeAtTheCounter() {
        assertThat(WaitTimeEstimator.remainingAtCounter(null, TEN_MINUTES, NOW)).isEqualTo(Duration.ZERO);
        assertThat(WaitTimeEstimator.remainingAtCounter(new CounterState(TicketStatus.CALLED, null), TEN_MINUTES, NOW))
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(WaitTimeEstimator.remainingAtCounter(
                new CounterState(TicketStatus.SERVING, NOW.minus(Duration.ofMinutes(4))), TEN_MINUTES, NOW))
                .isEqualTo(Duration.ofMinutes(6));
        // A service running longer than average is assumed to be about to finish, never negative.
        assertThat(WaitTimeEstimator.remainingAtCounter(
                new CounterState(TicketStatus.SERVING, NOW.minus(Duration.ofMinutes(25))), TEN_MINUTES, NOW))
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void estimateIsRemainingPlusOneAveragePerPersonAhead() {
        assertThat(WaitTimeEstimator.estimateWaitMinutes(0, Duration.ZERO, TEN_MINUTES)).isZero();
        assertThat(WaitTimeEstimator.estimateWaitMinutes(2, Duration.ZERO, TEN_MINUTES)).isEqualTo(20);
        assertThat(WaitTimeEstimator.estimateWaitMinutes(1, Duration.ofMinutes(6), TEN_MINUTES)).isEqualTo(16);
        // Partial minutes round up: an estimate is never optimistic by a fraction of a minute.
        assertThat(WaitTimeEstimator.estimateWaitMinutes(0, Duration.ofSeconds(61), TEN_MINUTES)).isEqualTo(2);
        assertThatThrownBy(() -> WaitTimeEstimator.estimateWaitMinutes(-1, Duration.ZERO, TEN_MINUTES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positionsAreOneBasedWithEstimatesForAnOrderedLine() {
        List<Position> positions = WaitTimeEstimator.positions(3, Duration.ofMinutes(5), TEN_MINUTES);
        assertThat(positions).containsExactly(
                new Position(1, 0, 5),
                new Position(2, 1, 15),
                new Position(3, 2, 25));
        assertThat(WaitTimeEstimator.positions(0, Duration.ZERO, TEN_MINUTES)).isEmpty();
    }
}
