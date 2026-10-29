package com.queueless.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queueless.common.error.ApiException;
import com.queueless.common.time.TimeProvider;
import com.queueless.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttemptLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private MutableClock clock;
    private AttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-03-10T04:30:00Z"));
        limiter = new AttemptLimiter(new TimeProvider(clock));
    }

    private void fail(String key, int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordAttempt(key, 5, WINDOW, LOCKOUT);
        }
    }

    @Test
    void blocksOnceTheLimitIsReachedInsideOneWindow() {
        fail("login-email:a@test.example", 4);
        assertThatCode(() -> limiter.checkNotBlocked("login-email:a@test.example")).doesNotThrowAnyException();

        fail("login-email:a@test.example", 1);
        assertThatThrownBy(() -> limiter.checkNotBlocked("login-email:a@test.example"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((ApiException) ex).retryAfterSeconds())
                        .isPositive());
        // Other keys are unaffected.
        assertThatCode(() -> limiter.checkNotBlocked("login-email:b@test.example")).doesNotThrowAnyException();
    }

    @Test
    void theBlockExpiresAfterTheLockout() {
        fail("k", 5);
        clock.advance(LOCKOUT.minusSeconds(1));
        assertThatThrownBy(() -> limiter.checkNotBlocked("k")).isInstanceOf(ApiException.class);
        clock.advance(Duration.ofSeconds(2));
        assertThatCode(() -> limiter.checkNotBlocked("k")).doesNotThrowAnyException();
    }

    @Test
    void attemptsSpreadOverSeveralWindowsDoNotBlock() {
        fail("k", 4);
        clock.advance(WINDOW.plusSeconds(1));
        fail("k", 4);
        assertThatCode(() -> limiter.checkNotBlocked("k")).doesNotThrowAnyException();
    }

    @Test
    void resetClearsTheCounter() {
        fail("k", 4);
        limiter.reset("k");
        fail("k", 4);
        assertThatCode(() -> limiter.checkNotBlocked("k")).doesNotThrowAnyException();
    }
}
