package com.gr74.booking.exception;

import java.util.stream.Collectors;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * Renders every booking error as an RFC 9457 ProblemDetail with a stable machine-readable {@code code}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Custom {@code ProblemDetail} member that holds the {@link BookingErrorCode} name. */
    private static final String CODE_PROPERTY = "code";

    /** Shared log template for every validation-failure path (keeps the log message consistent). */
    private static final String VALIDATION_FAILED_LOG = "Validation failed: {}";

    /** Deliberate domain errors; the carried code drives both status and {@code code}. */
    @ExceptionHandler(BookingException.class)
    public ProblemDetail handleBookingException(BookingException ex) {
        BookingErrorCode code = ex.errorCode();
        if (code.status().is5xxServerError()) {
            log.error("Booking error [{}]: {}", code, ex.getMessage(), ex);
        } else {
            log.warn("Booking error [{}]: {}", code, ex.getMessage());
        }
        return problemDetail(code, ex.getMessage());
    }

    /** DB constraint violation translated to the same {@code BOOKING_DUPLICATE} (409) as the pre-check. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation (treated as duplicate): {}", ex.getMostSpecificCause().getMessage());
        return problemDetail(BookingErrorCode.BOOKING_DUPLICATE,
                "The resource conflicts with an existing one (duplicate or constraint violation)");
    }

    /** Unconvertible path variable / request param; treated as a validation error. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = "Parameter '" + ex.getName() + "' has an invalid value: " + ex.getValue();
        log.warn(VALIDATION_FAILED_LOG, detail);
        return problemDetail(BookingErrorCode.BOOKING_VALIDATION_ERROR, detail);
    }

    /** Method-security denial rendered as the same coded {@code BOOKING_FORBIDDEN} (403). */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ProblemDetail handleAuthorizationDenied(AuthorizationDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return problemDetail(BookingErrorCode.BOOKING_FORBIDDEN,
                "Insufficient permissions for this resource");
    }

    /** Catch-all: unanticipated errors become 500 with a safe message and full logging. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception in booking service", ex);
        return problemDetail(BookingErrorCode.BOOKING_INTERNAL_ERROR,
                "An unexpected error occurred");
    }

    /** Body validation failures, with field violations folded into the detail. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));

        ProblemDetail body = problemDetail(BookingErrorCode.BOOKING_VALIDATION_ERROR,
                detail.isBlank() ? "Request validation failed" : detail);
        log.warn(VALIDATION_FAILED_LOG, detail);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    /** Method-parameter constraint failures; same code, same shape. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        String detail = ex.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining("; "));

        ProblemDetail body = problemDetail(BookingErrorCode.BOOKING_VALIDATION_ERROR,
                detail.isBlank() ? "Request validation failed" : detail);
        log.warn(VALIDATION_FAILED_LOG, detail);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    /** Malformed body or unknown enum value; generic detail that leaks no internals. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        ProblemDetail body = problemDetail(BookingErrorCode.BOOKING_VALIDATION_ERROR,
                "Request body is malformed or contains an invalid value");
        log.warn("Unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    /** Builds a ProblemDetail with the status from the code and the {@code code} attached. */
    private ProblemDetail problemDetail(BookingErrorCode code, String detail) {
        HttpStatus status = code.status();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(code.title());
        problem.setProperty(CODE_PROPERTY, code.name());
        return problem;
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
