package com.queueless.common.error;

import java.util.Map;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Translates database constraint names into API errors. Most rules are checked by services
 * first; these mappings cover the races where the database constraint is the one that
 * actually rejected the write.
 */
final class ConstraintErrors {

    private record Mapping(ErrorCode code, String detail) {}

    private static final Map<String, Mapping> MAPPINGS = Map.of(
            "uq_users_email", new Mapping(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email already exists"),
            "ux_appointments_active_per_user_day", new Mapping(ErrorCode.ALREADY_BOOKED, "You already have a booking in this queue on that day"),
            "ux_tickets_active_per_user_day", new Mapping(ErrorCode.ALREADY_IN_QUEUE, "You already hold an active ticket in this queue today"),
            "ux_tickets_active_per_queue_day", new Mapping(ErrorCode.ACTIVE_TICKET_EXISTS, "Another ticket is already being served in this queue"),
            "uq_memberships_user_org", new Mapping(ErrorCode.MEMBERSHIP_EXISTS, "This user is already a member of the organization"),
            "uq_queues_org_name", new Mapping(ErrorCode.DUPLICATE_NAME, "A queue with this name already exists in the organization"),
            "uq_organizations_slug", new Mapping(ErrorCode.DUPLICATE_NAME, "An organization with this slug already exists"),
            "uq_appointments_idempotency", new Mapping(ErrorCode.IDEMPOTENCY_KEY_REUSED, "This idempotency key was already used"),
            "uq_tickets_token", new Mapping(ErrorCode.QUEUE_BUSY, "Token allocation conflicted with another request; please retry"));

    private ConstraintErrors() {}

    static Optional<ApiException> translate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                Mapping mapping = MAPPINGS.get(violation.getConstraintName().toLowerCase());
                if (mapping != null) {
                    return Optional.of(new ApiException(mapping.code(), mapping.detail()));
                }
            }
        }
        return Optional.empty();
    }
}
