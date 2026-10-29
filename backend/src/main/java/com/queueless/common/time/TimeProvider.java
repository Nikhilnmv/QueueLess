package com.queueless.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * Single source of "now" for business logic, backed by an injectable {@link Clock} so tests
 * can control time.
 *
 * <p>Instants are truncated to microseconds because PostgreSQL {@code timestamptz} stores
 * microseconds: a value compared in Java is then exactly the value stored in the database,
 * which keeps ordering by check-in time identical in both places.
 */
@Component
public class TimeProvider {

    private final Clock clock;

    public TimeProvider(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Today's calendar date in the given (organization) time zone. */
    public LocalDate today(ZoneId zone) {
        return LocalDate.ofInstant(now(), zone);
    }
}
