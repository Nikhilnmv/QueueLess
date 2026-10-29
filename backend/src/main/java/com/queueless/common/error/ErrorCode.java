package com.queueless.common.error;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes. Every error response is an RFC 9457 problem detail whose
 * {@code code} property is one of these names, so clients never parse human-readable text.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    INVALID_SLOT(HttpStatus.BAD_REQUEST, "Invalid appointment slot"),

    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),

    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
    CSRF_REJECTED(HttpStatus.FORBIDDEN, "Missing or invalid CSRF token"),

    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),

    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email already registered"),
    SLOT_FULL(HttpStatus.CONFLICT, "Appointment slot is full"),
    ALREADY_BOOKED(HttpStatus.CONFLICT, "Already booked"),
    ALREADY_IN_QUEUE(HttpStatus.CONFLICT, "Already in this queue"),
    BOOKING_DISABLED(HttpStatus.CONFLICT, "Bookings are disabled"),
    QUEUE_NOT_ACCEPTING(HttpStatus.CONFLICT, "Queue is not accepting customers"),
    QUEUE_NOT_OPEN(HttpStatus.CONFLICT, "Queue is not open"),
    ACTIVE_TICKET_EXISTS(HttpStatus.CONFLICT, "A ticket is already being served"),
    NO_WAITING_TICKETS(HttpStatus.CONFLICT, "Nobody is waiting"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid state transition"),
    CHECK_IN_NOT_ALLOWED(HttpStatus.CONFLICT, "Check-in is not allowed now"),
    MEMBERSHIP_EXISTS(HttpStatus.CONFLICT, "Membership already exists"),
    LAST_ADMIN(HttpStatus.CONFLICT, "Organization needs an administrator"),
    DUPLICATE_NAME(HttpStatus.CONFLICT, "Name already in use"),
    CONFLICT(HttpStatus.CONFLICT, "Conflicting request"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused"),

    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    QUEUE_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Queue is busy"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
