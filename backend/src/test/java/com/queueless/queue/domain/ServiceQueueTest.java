package com.queueless.queue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServiceQueueTest {

    private static final Instant NOW = Instant.parse("2026-03-10T04:30:00Z");

    private static ServiceQueue queue() {
        return new ServiceQueue(UUID.randomUUID(), new QueueSettings("General OPD", null, "G", LocalTime.of(9, 0),
                LocalTime.of(17, 0), 15, 2, 10, true), NOW);
    }

    @Test
    void newQueuesStartClosed() {
        assertThat(queue().getStatus()).isEqualTo(QueueStatus.CLOSED);
    }

    @Test
    void statusDecidesWhatIsAllowed() {
        ServiceQueue queue = queue();
        queue.changeStatus(QueueStatus.OPEN, NOW);
        assertThat(queue.acceptsNewArrivals()).isTrue();
        assertThat(queue.canCallNext()).isTrue();

        queue.changeStatus(QueueStatus.PAUSED, NOW);
        assertThat(queue.acceptsNewArrivals()).isTrue();
        assertThat(queue.canCallNext()).isFalse();

        queue.changeStatus(QueueStatus.CLOSED, NOW);
        assertThat(queue.acceptsNewArrivals()).isFalse();
        assertThat(queue.canCallNext()).isFalse();
    }

    @Test
    void displayTokensArePrefixedAndPadded() {
        assertThat(queue().displayToken(7)).isEqualTo("G-007");
        assertThat(queue().displayToken(1234)).isEqualTo("G-1234");
    }

    @Test
    void everyLiveChangeIncrementsTheStateVersion() {
        ServiceQueue queue = queue();
        assertThat(queue.bumpStateVersion(NOW)).isEqualTo(1);
        assertThat(queue.bumpStateVersion(NOW)).isEqualTo(2);
    }
}
