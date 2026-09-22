package com.gr74.payment.exception;

import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders every service error as an RFC 9457 ProblemDetail with a stable {@code code}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Property holding the {@link PaymentErrorCode} name. */
    private static final String CODE_PROPERTY = "code";

    /** Deliberate domain errors; status and code come from the carried {@link PaymentErrorCode}. */
    @ExceptionHandler(PaymentException.class)
    public ProblemDetail handlePaymentException(PaymentException ex) {
        PaymentErrorCode code = ex.errorCode();
        // log at warn without the noise.
        if (code.status().is5xxServerError()) {
            log.error("Payment error [{}]: {}", code, ex.getMessage(), ex);
        } else {
            log.warn("Payment error [{}]: {}", code, ex.getMessage());
        }
        return problemDetail(code, ex.getMessage());
    }

    /** Method-security denials (e.g. USER calling an ADMIN-only endpoint) as 403. */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ProblemDetail handleAuthorizationDenied(AuthorizationDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return problemDetail(PaymentErrorCode.PAYMENT_ACCESS_DENIED,
                "Insufficient permissions for this resource");
    }

    /** Catch-all: unanticipated errors become 500 without leaking internals. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception in payment service", ex);
        return problemDetail(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                "An unexpected error occurred while processing the payment");
    }

    /** Open circuit breaker: same coded 503 as a real outage, returned immediately. */
    @ExceptionHandler(CallNotPermittedException.class)
    public ProblemDetail handleCallNotPermitted(CallNotPermittedException ex) {
        // The exception message names the breaker.
        log.warn("Gateway circuit breaker open, failing fast: {}", ex.getMessage());
        return problemDetail(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                "Payment gateway is unavailable: " + ex.getMessage());
    }

    /** Full bulkhead: coded 429 so clients back off. */
    @ExceptionHandler(BulkheadFullException.class)
    public ProblemDetail handleBulkheadFull(BulkheadFullException ex) {
        log.warn("Gateway bulkhead full, rejecting call: {}", ex.getMessage());
        return problemDetail(PaymentErrorCode.PAYMENT_GATEWAY_BUSY,
                "Payment gateway is at capacity: " + ex.getMessage());
    }

    /** Bean-validation failures on {@code @Valid @RequestBody}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));

        ProblemDetail body = problemDetail(PaymentErrorCode.PAYMENT_VALIDATION_ERROR,
                detail.isBlank() ? "Request validation failed" : detail);
        log.warn("Validation failed: {}", detail);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    /** Constraint failures on controller parameters. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        String detail = ex.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining("; "));

        ProblemDetail body = problemDetail(PaymentErrorCode.PAYMENT_VALIDATION_ERROR,
                detail.isBlank() ? "Request validation failed" : detail);
        log.warn("Validation failed: {}", detail);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    /** Unparseable bodies become validation errors with a generic detail. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        ProblemDetail body = problemDetail(PaymentErrorCode.PAYMENT_VALIDATION_ERROR,
                "Request body is malformed or contains an unsupported value");
        log.warn("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    /** Missing required headers become validation errors. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        String detail = "Required header '" + ex.getHeaderName() + "' is missing";
        log.warn("Validation failed: {}", detail);
        return problemDetail(PaymentErrorCode.PAYMENT_VALIDATION_ERROR, detail);
    }

    /** Builds a ProblemDetail with the code's status and the flat {@code code} property. */
    private ProblemDetail problemDetail(PaymentErrorCode code, String detail) {
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
