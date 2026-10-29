package com.queueless.queue.api;

import com.queueless.organization.domain.Organization;
import com.queueless.queue.domain.QueueSettings;
import com.queueless.queue.domain.QueueStatus;
import com.queueless.queue.domain.ServiceQueue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.Locale;
import java.util.UUID;

public final class QueueDtos {

    private QueueDtos() {}

    /** Queue configuration and status. Contains no customer data, so it is public. */
    public record QueueResponse(
            UUID id,
            UUID organizationId,
            String organizationName,
            String organizationSlug,
            String timezone,
            String name,
            String description,
            String tokenPrefix,
            QueueStatus status,
            boolean bookingEnabled,
            LocalTime openingTime,
            LocalTime closingTime,
            int slotDurationMinutes,
            int capacityPerSlot,
            int defaultServiceMinutes,
            long stateVersion) {

        public static QueueResponse from(ServiceQueue queue, Organization organization) {
            return new QueueResponse(queue.getId(), organization.getId(), organization.getName(),
                    organization.getSlug(), organization.getTimezone(), queue.getName(), queue.getDescription(),
                    queue.getTokenPrefix(), queue.getStatus(), queue.isBookingEnabled(), queue.getOpeningTime(),
                    queue.getClosingTime(), queue.getSlotDurationMinutes(), queue.getCapacityPerSlot(),
                    queue.getDefaultServiceMinutes(), queue.getStateVersion());
        }
    }

    /** Create/update request. Status, version and organization cannot be set through it. */
    public record QueueConfigRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{1,5}$", message = "must be 1 to 5 letters") String tokenPrefix,
            @NotNull LocalTime openingTime,
            @NotNull LocalTime closingTime,
            @Min(5) @Max(240) int slotDurationMinutes,
            @Min(1) @Max(100) int capacityPerSlot,
            @Min(1) @Max(240) int defaultServiceMinutes,
            @NotNull Boolean bookingEnabled) {

        public QueueSettings toSettings() {
            String trimmedDescription = description == null || description.isBlank() ? null : description.trim();
            return new QueueSettings(name.trim(), trimmedDescription, tokenPrefix.toUpperCase(Locale.ROOT),
                    openingTime.withSecond(0).withNano(0), closingTime.withSecond(0).withNano(0),
                    slotDurationMinutes, capacityPerSlot, defaultServiceMinutes, bookingEnabled);
        }
    }

    public record ChangeQueueStatusRequest(@NotNull QueueStatus status) {}

    /** First event on a queue stream. */
    public record StreamReady(UUID queueId, long version) {}
}
