package com.queueless.ticket.domain;

import static com.queueless.ticket.domain.TicketStatus.BOOKED;
import static com.queueless.ticket.domain.TicketStatus.CALLED;
import static com.queueless.ticket.domain.TicketStatus.CANCELLED;
import static com.queueless.ticket.domain.TicketStatus.COMPLETED;
import static com.queueless.ticket.domain.TicketStatus.NO_SHOW;
import static com.queueless.ticket.domain.TicketStatus.SERVING;
import static com.queueless.ticket.domain.TicketStatus.WAITING;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TicketStatusTest {

    /** The complete transition table from the specification; everything else is illegal. */
    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED = Map.of(
            BOOKED, EnumSet.of(WAITING, CANCELLED),
            WAITING, EnumSet.of(CALLED, CANCELLED),
            CALLED, EnumSet.of(SERVING, NO_SHOW),
            SERVING, EnumSet.of(COMPLETED),
            COMPLETED, EnumSet.noneOf(TicketStatus.class),
            NO_SHOW, EnumSet.noneOf(TicketStatus.class),
            CANCELLED, EnumSet.noneOf(TicketStatus.class));

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void onlySpecifiedTransitionsAreAllowed(TicketStatus from) {
        for (TicketStatus to : TicketStatus.values()) {
            assertThat(from.canTransitionTo(to))
                    .as("%s -> %s", from, to)
                    .isEqualTo(ALLOWED.get(from).contains(to));
        }
    }

    @Test
    void terminalStatesCannotChange() {
        assertThat(EnumSet.of(COMPLETED, NO_SHOW, CANCELLED)).allSatisfy(status -> {
            assertThat(status.isTerminal()).isTrue();
            assertThat(status.allowedNextStates()).isEmpty();
        });
    }

    @Test
    void aCancelledTicketCanNeverReEnterTheQueue() {
        assertThat(CANCELLED.canTransitionTo(WAITING)).isFalse();
        assertThat(CANCELLED.canTransitionTo(BOOKED)).isFalse();
    }

    @Test
    void activeAndCounterSets() {
        assertThat(TicketStatus.ACTIVE).containsExactlyInAnyOrder(BOOKED, WAITING, CALLED, SERVING);
        assertThat(TicketStatus.AT_COUNTER).containsExactlyInAnyOrder(CALLED, SERVING);
    }
}
