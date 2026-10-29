package com.queueless.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock tests can set and advance; injected as the primary Clock bean in integration tests. */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    public MutableClock(Instant initial) {
        this.now = new AtomicReference<>(initial);
    }

    public void setInstant(Instant instant) {
        now.set(instant);
    }

    public void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
