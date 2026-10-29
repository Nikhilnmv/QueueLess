package com.queueless.auth.service;

import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import com.queueless.common.time.TimeProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lightweight, in-memory fixed-window limiter used against password guessing and sign-up spam.
 *
 * <p>Single-instance by design (documented limitation): with several application instances each
 * would count separately, and a shared store such as Redis or a database table would be needed.
 */
@Component
public class AttemptLimiter {

    private record Window(Instant start, int count, Instant blockedUntil) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final TimeProvider time;

    public AttemptLimiter(TimeProvider time) {
        this.time = time;
    }

    /** Throws 429 Too Many Requests while the key is blocked. */
    public void checkNotBlocked(String key) {
        Window window = windows.get(key);
        Instant now = time.now();
        if (window != null && window.blockedUntil() != null && now.isBefore(window.blockedUntil())) {
            long seconds = Math.max(1, Duration.between(now, window.blockedUntil()).toSeconds());
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                    "Too many attempts. Try again in " + Math.max(1, (seconds + 59) / 60) + " minute(s).")
                    .retryAfter(seconds);
        }
    }

    /** Counts an attempt; once {@code limit} attempts fall in one window the key is blocked. */
    public void recordAttempt(String key, int limit, Duration window, Duration blockFor) {
        Instant now = time.now();
        windows.compute(key, (k, current) -> {
            boolean stillBlocked = current != null && current.blockedUntil() != null && now.isBefore(current.blockedUntil());
            Window active = (current == null || (!stillBlocked && !now.isBefore(current.start().plus(window))))
                    ? new Window(now, 0, null)
                    : current;
            int count = active.count() + 1;
            Instant blockedUntil = count >= limit ? now.plus(blockFor) : active.blockedUntil();
            return new Window(active.start(), count, blockedUntil);
        });
    }

    public void reset(String key) {
        windows.remove(key);
    }

    /** Drops expired windows so the map cannot grow without bound. */
    @Scheduled(fixedDelayString = "PT10M")
    void evictExpired() {
        Instant cutoff = time.now().minus(Duration.ofHours(2));
        windows.entrySet().removeIf(entry -> {
            Window w = entry.getValue();
            Instant lastRelevant = w.blockedUntil() != null && w.blockedUntil().isAfter(w.start()) ? w.blockedUntil() : w.start();
            return lastRelevant.isBefore(cutoff);
        });
    }
}
