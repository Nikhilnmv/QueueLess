package com.queueless.common.error;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 problem details with QueueLess conventions (type URN and {@code code} property). */
public final class Problems {

    private Problems() {}

    public static ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        problem.setType(URI.create("urn:queueless:problem:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code.name());
        return problem;
    }
}
