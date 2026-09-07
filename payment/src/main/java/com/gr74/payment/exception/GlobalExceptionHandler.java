package com.gr74.payment.exception;

import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns every error this service produces into an RFC 9457 {@code ProblemDetail}
 * ({@code application/problem+json}) carrying a stable, machine-readable {@code code}.
 *
 * <p>Why this exists: without a single advice, a bad request falls back to Spring's default body
 * ({@code {"error":"Bad Request",...}}) which has no field a caller can branch on. By extending
 * {@link ResponseEntityExceptionHandler} we reuse Spring's own handling of framework errors (bean
 * validation, unreadable bodies, missing headers) and only add the {@code code} property, so
 * <em>every</em> error — ours or the framework's — speaks the same contract. Booking's saga keys off
 * {@code code}, not the HTTP status or the human-readable {@code detail}. See
 * {@code docs/concepts/error-handling-problemdetail.md}.
 *
 * <p>The {@code code} comes from {@link PaymentErrorCode}; the HTTP status is taken from the code so
 * the two can never drift apart.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Custom {@code ProblemDetail} member that holds the {@link PaymentErrorCode} name. */
    private static final String CODE_PROPERTY = "code";

    /**
     * Any error we raised deliberately. The carried {@link PaymentErrorCode} drives both the HTTP
     * status and the {@code code}, so a new failure mode is one enum constant plus a throw — no
     * change here.
     */
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

    /**
     * Last line of defence: anything we did not anticipate becomes a 500 with the generic
     * {@code PAYMENT_INTERNAL_ERROR} code and a safe message — we never leak the exception text to
     * the caller, but we log the full detail for ourselves.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception in payment service", ex);
        return problemDetail(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                "An unexpected error occurred while processing the payment");
    }

    /**
     * Bean-validation failures on {@code @Valid @RequestBody} (e.g. a non-positive amount). Spring
     * raises this before our code runs; we override its hook so the response gets a {@code code}
     * too, with the field violations folded into the detail.
     */
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

    /**
     * Method-level constraint failures on controller parameters — here, the
     * {@code @NotBlank X-User-Id} header (Spring 6.1+ raises this rather than
     * {@link MethodArgumentNotValidException} for non-body params). Same {@code code}, same shape.
     */
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

    /**
     * The request body could not be parsed at all — malformed JSON, or a value that cannot be bound
     * to its target type (e.g. {@code "gateway":"NOT_A_GATEWAY"} for the
     * {@link com.gr74.payment.model.PaymentGatewayType} enum).
     *
     * <p>Spring's own handling produces a {@code ProblemDetail} with <b>no {@code code}</b>, which
     * quietly breaks this service's contract: a client that branches on {@code code} would see the
     * field simply missing. Overriding the hook keeps every error — ours and the framework's —
     * speaking the same shape.
     *
     * <p>The detail is deliberately generic. Jackson's own message names internal class and field
     * paths, which is information the caller neither needs nor should see.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        ProblemDetail body = problemDetail(PaymentErrorCode.PAYMENT_VALIDATION_ERROR,
                "Request body is malformed or contains an unsupported value");
        log.warn("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    /**
     * A required header is absent altogether (e.g. no {@code X-User-Id}). Treated as the
     * caller's validation error so it carries {@code PAYMENT_VALIDATION_ERROR} like the others.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        String detail = "Required header '" + ex.getHeaderName() + "' is missing";
        log.warn("Validation failed: {}", detail);
        return problemDetail(PaymentErrorCode.PAYMENT_VALIDATION_ERROR, detail);
    }

    /** Build a {@code ProblemDetail} with the status from the code and the {@code code} attached. */
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
