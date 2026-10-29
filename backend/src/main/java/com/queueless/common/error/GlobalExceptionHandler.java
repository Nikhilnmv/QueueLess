package com.queueless.common.error;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Converts every failure into a consistent problem-detail response. Stack traces, SQL and
 * other internal details are logged, never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        return toResponse(ex);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        ApiException translated = ConstraintErrors.translate(ex).orElseGet(() ->
                new ApiException(ErrorCode.CONFLICT, "The request conflicts with the current state of the resource"));
        log.info("Constraint violation translated to {}: {}", translated.code(), ex.getMostSpecificCause().getMessage());
        return toResponse(translated);
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, QueryTimeoutException.class,
            TransactionTimedOutException.class})
    ResponseEntity<ProblemDetail> handleLockTimeout(RuntimeException ex) {
        log.warn("Lock or transaction timeout: {}", ex.getMessage());
        return toResponse(new ApiException(ErrorCode.QUEUE_BUSY, "The queue is busy. Please retry in a moment.")
                .retryAfter(2));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList();
        return toResponse(new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid")
                .with("errors", errors));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return toResponse(new ApiException(ErrorCode.MALFORMED_REQUEST,
                "Invalid value for parameter '" + ex.getName() + "'"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return toResponse(ApiException.accessDenied("You do not have permission to perform this action"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        String requestId = MDC.get("requestId");
        log.error("Unhandled exception (requestId={})", requestId, ex);
        ApiException error = new ApiException(ErrorCode.INTERNAL_ERROR, "Something went wrong. Please try again.");
        if (requestId != null) {
            error.with("requestId", requestId);
        }
        return toResponse(error);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::fieldError)
                .toList();
        ApiException error = new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid")
                .with("errors", errors);
        return asObject(toResponse(error));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getAllErrors().stream()
                .map(e -> Map.of("field", e instanceof FieldError f ? f.getField() : "request",
                        "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        return asObject(toResponse(new ApiException(ErrorCode.VALIDATION_FAILED, "One or more parameters are invalid")
                .with("errors", errors)));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return asObject(toResponse(new ApiException(ErrorCode.MALFORMED_REQUEST,
                "The request body is missing or is not valid JSON")));
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return asObject(toResponse(ApiException.notFound("Resource")));
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return asObject(toResponse(new ApiException(ErrorCode.METHOD_NOT_ALLOWED,
                "Method " + ex.getMethod() + " is not supported for this URL")));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return asObject(toResponse(new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Send the request body as application/json")));
    }

    /** Adds the {@code code} property to the remaining Spring MVC errors (400/404/406/...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem && problem.getProperties() == null) {
            problem.setProperty("code", switch (statusCode.value()) {
                case 404 -> ErrorCode.NOT_FOUND.name();
                case 405 -> ErrorCode.METHOD_NOT_ALLOWED.name();
                case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE.name();
                case 503 -> ErrorCode.QUEUE_BUSY.name();
                default -> statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR.name() : ErrorCode.MALFORMED_REQUEST.name();
            });
        }
        return response;
    }

    private static Map<String, String> fieldError(FieldError error) {
        return Map.of("field", error.getField(),
                "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage());
    }

    private static ResponseEntity<ProblemDetail> toResponse(ApiException ex) {
        ProblemDetail problem = Problems.of(ex.code(), ex.getMessage());
        ex.properties().forEach(problem::setProperty);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.code().status());
        if (ex.retryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        }
        return builder.body(problem);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<Object> asObject(ResponseEntity<ProblemDetail> response) {
        return (ResponseEntity) response;
    }
}
