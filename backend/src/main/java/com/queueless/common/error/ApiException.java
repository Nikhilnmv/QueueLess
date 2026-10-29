package com.queueless.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A business or authorization failure that maps to a specific HTTP status and error code.
 * Services throw it; {@link GlobalExceptionHandler} turns it into a problem-detail response.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private Long retryAfterSeconds;

    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public static ApiException notFound(String resource) {
        return new ApiException(ErrorCode.NOT_FOUND, resource + " not found");
    }

    public static ApiException accessDenied(String detail) {
        return new ApiException(ErrorCode.ACCESS_DENIED, detail);
    }

    /** Adds an extra property to the problem detail (for example the id of a conflicting resource). */
    public ApiException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public ApiException retryAfter(long seconds) {
        this.retryAfterSeconds = seconds;
        return this;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
