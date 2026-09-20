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
 * Turns every error the booking service produces into an RFC 9457 {@code ProblemDetail}
 * ({@code application/problem+json}) carrying a stable, machine-readable {@code code}.
 *
 * <p>Why this exists: without a single advice, a bad request falls back to Spring's default body which
 * has no field a caller can branch on. By extending {@link ResponseEntityExceptionHandler} we reuse
 * Spring's own handling of framework errors (bean validation, missing/typed params) and only add the
 * {@code code} property, so <em>every</em> error — ours or the framework's — speaks the same contract.
 * See {@code docs/concepts/error-handling-problemdetail.md}; {@code payment/} is the reference impl.
 *
 * <p>The {@code code} comes from {@link BookingErrorCode}; the HTTP status is taken from the code so
 * the two can never drift apart.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Custom {@code ProblemDetail} member that holds the {@link BookingErrorCode} name. */
    private static final String CODE_PROPERTY = "code";

    /** Shared log template for every validation-failure path (keeps the log message consistent). */
    private static final String VALIDATION_FAILED_LOG = "Validation failed: {}";

    /**
     * Any error we raised deliberately (not-found, duplicate, movie-not-in-catalog, catalog-down). The
     * carried {@link BookingErrorCode} drives both the HTTP status and the {@code code}, so a new
     * failure mode is one enum constant plus a throw — no change here.
     */
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

    /**
     * A uniqueness constraint fired at the database instead of our pre-check — the concurrent-duplicate
     * race the pre-check can't see (two requests both pass the existence check, then both insert). The
     * DB constraint is the real guard; we translate its {@link DataIntegrityViolationException} into
     * the same {@code BOOKING_DUPLICATE} (409) the pre-check would have produced, so the caller sees
     * one consistent contract either way.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation (treated as duplicate): {}", ex.getMostSpecificCause().getMessage());
        return problemDetail(BookingErrorCode.BOOKING_DUPLICATE,
                "The resource conflicts with an existing one (duplicate or constraint violation)");
    }

    /**
     * A path variable / request param could not be converted to the expected type — e.g. a theater id
     * that isn't a {@code Long} ({@code GET /theaters/abc}). Treated as the caller's validation error.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = "Parameter '" + ex.getName() + "' has an invalid value: " + ex.getValue();
        log.warn(VALIDATION_FAILED_LOG, detail);
        return problemDetail(BookingErrorCode.BOOKING_VALIDATION_ERROR, detail);
    }

    /**
     * A {@code @PreAuthorize} (or any method-security rule) denied the call — e.g. a {@code USER}
     * calling an {@code ADMIN} inventory write.
     *
     * <p><b>Why this handler must exist:</b> a method-security denial is thrown from the controller
     * proxy <em>inside</em> the {@code DispatcherServlet} — <em>after</em> the security filter
     * chain (and its {@code AccessDeniedHandler}) has already run. The filter-chain handler can
     * never see it, so without this method the denial falls through to the catch-all below as an
     * opaque 500. Rendering it here as the same coded {@code BOOKING_FORBIDDEN} (403) the chain
     * produces keeps one contract for both denial paths: URL-level (chain) and method-level
     * (advice) are indistinguishable to the caller, as they should be.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ProblemDetail handleAuthorizationDenied(AuthorizationDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return problemDetail(BookingErrorCode.BOOKING_FORBIDDEN,
                "Insufficient permissions for this resource");
    }

    /**
     * Last line of defence: anything we did not anticipate becomes a 500 with the generic
     * {@code BOOKING_INTERNAL_ERROR} code and a safe message — we never leak the exception text to the
     * caller, but we log the full detail for ourselves.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception in booking service", ex);
        return problemDetail(BookingErrorCode.BOOKING_INTERNAL_ERROR,
                "An unexpected error occurred");
    }

    /**
     * Bean-validation failures on a {@code @Valid @RequestBody} (e.g. a blank theater name, a
     * non-positive price). Spring raises this before our code runs; we override its hook so the
     * response gets a {@code code} too, with the field violations folded into the detail.
     */
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

    /**
     * Method-level constraint failures on controller parameters (Spring 6.1+ raises this for non-body
     * params, e.g. a {@code @Min} on a path variable). Same {@code code}, same shape.
     */
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

    /**
     * A malformed or unreadable request body — most often an <em>unknown enum value</em> (e.g.
     * {@code screenType: "HOLOGRAM"}) or syntactically broken JSON that Jackson can't deserialize.
     * Spring raises this before our code runs; the base handler would return a body with no
     * {@code code}, so we override its hook to attach {@code BOOKING_VALIDATION_ERROR} — same contract
     * as every other bad-request. We don't echo the parser message (it can leak internals); a stable,
     * generic detail is enough for the caller to know the body was rejected.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        ProblemDetail body = problemDetail(BookingErrorCode.BOOKING_VALIDATION_ERROR,
                "Request body is malformed or contains an invalid value");
        log.warn("Unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    /** Build a {@code ProblemDetail} with the status from the code and the {@code code} attached. */
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
