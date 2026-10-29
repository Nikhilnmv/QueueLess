package com.queueless.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Application settings bound from the {@code queueless.*} properties in application.yml. */
@Validated
@ConfigurationProperties(prefix = "queueless")
public record QueueLessProperties(
        @Valid @NotNull Booking booking,
        @Valid @NotNull CheckIn checkIn,
        @Valid @NotNull Notifications notifications,
        @Valid @NotNull Estimation estimation,
        @Valid @NotNull Realtime realtime,
        @Valid @NotNull Security security,
        @Valid @NotNull Seed seed) {

    /** How far ahead (in organization-local days) customers may book. */
    public record Booking(@Min(1) @Max(90) int maxDaysAhead) {}

    /** How many minutes before the appointment time check-in opens. */
    public record CheckIn(@Min(0) @Max(240) int earlyMinutes) {}

    /** A waiting customer is told their turn is approaching at this queue position or better. */
    public record Notifications(@Min(1) @Max(20) int approachPosition) {}

    /** Waiting-time estimation: recent completed services used, and the minimum before history is trusted. */
    public record Estimation(@Min(1) @Max(200) int sampleSize, @Min(1) int minSamples) {}

    public record Realtime(
            @NotNull Duration emitterTimeout,
            @NotNull Duration heartbeatInterval,
            @NotNull Duration reconnectDelay,
            @Min(1) int maxConnectionsPerUser) {}

    public record Security(boolean httpsRedirect, @Valid @NotNull Login login, @Valid @NotNull Registration registration) {}

    public record Login(
            @Min(1) int maxFailuresPerEmail,
            @Min(1) int maxFailuresPerIp,
            @NotNull Duration window,
            @NotNull Duration lockout) {}

    public record Registration(@Min(1) int maxPerIp, @NotNull Duration window) {}

    public record Seed(boolean enabled, boolean sampleTickets, String password) {}
}
