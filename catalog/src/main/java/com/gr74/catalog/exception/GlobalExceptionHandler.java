package com.gr74.catalog.exception;

import java.util.stream.Collectors;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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
 * Turns every error this service produces into an RFC 9457 {@code ProblemDetail}
 * ({@code application/problem+json}) carrying a stable, machine-readable {@code code}.
 *
 * <p>Why this exists: without a single advice, a bad request falls back to Spring's default body
 * which has no field a caller can branch on. By extending {@link ResponseEntityExceptionHandler} we
 * reuse Spring's own handling of framework errors (bean validation, missing/typed params) and only
 * add the {@code code} property, so <em>every</em> error — ours or the framework's — speaks the same
 * contract. See {@code docs/concepts/error-handling-problemdetail.md}; {@code payment/} is the
 * reference implementation.
 *
 * <p>The {@code code} comes from {@link CatalogErrorCode}; the HTTP status is taken from the code so
 * the two can never drift apart.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Custom {@code ProblemDetail} member that holds the {@link CatalogErrorCode} name. */
    private static final String CODE_PROPERTY = "code";

    /** Shared log template for every validation-failure path (keeps the log message consistent). */
    private static final String VALIDATION_FAILED_LOG = "Validation failed: {}";

    /**
     * Any error we raised deliberately (e.g. {@link MovieNotFoundException}). The carried
     * {@link CatalogErrorCode} drives both the HTTP status and the {@code code}, so a new failure
     * mode is one enum constant plus a throw — no change here.
     */
    @ExceptionHandler(CatalogException.class)
    public ProblemDetail handleCatalogException(CatalogException ex) {
        CatalogErrorCode code = ex.errorCode();
        if (code.status().is5xxServerError()) {
            log.error("Catalog error [{}]: {}", code, ex.getMessage(), ex);
        } else {
            log.warn("Catalog error [{}]: {}", code, ex.getMessage());
        }
        return problemDetail(code, ex.getMessage());
    }

    /**
     * A path variable / request param could not be converted to the expected type — here, a movie id
     * that isn't a {@code Long} (e.g. {@code GET /movies/abc}). Treated as the caller's validation
     * error.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = "Parameter '" + ex.getName() + "' has an invalid value: " + ex.getValue();
        log.warn(VALIDATION_FAILED_LOG, detail);
        return problemDetail(CatalogErrorCode.CATALOG_VALIDATION_ERROR, detail);
    }

    /**
     * Last line of defence: anything we did not anticipate becomes a 500 with the generic
     * {@code CATALOG_INTERNAL_ERROR} code and a safe message — we never leak the exception text to
     * the caller, but we log the full detail for ourselves.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception in catalog service", ex);
        return problemDetail(CatalogErrorCode.CATALOG_INTERNAL_ERROR,
                "An unexpected error occurred");
    }

    /**
     * Bean-validation failures on {@code @Valid @RequestBody} (relevant once write paths exist).
     * Spring raises this before our code runs; we override its hook so the response gets a
     * {@code code} too, with the field violations folded into the detail.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));

        ProblemDetail body = problemDetail(CatalogErrorCode.CATALOG_VALIDATION_ERROR,
                detail.isBlank() ? "Request validation failed" : detail);
        log.warn(VALIDATION_FAILED_LOG, detail);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    /**
     * Method-level constraint failures on controller parameters (Spring 6.1+ raises this for non-body
     * params). Same {@code code}, same shape.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        String detail = ex.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining("; "));

        ProblemDetail body = problemDetail(CatalogErrorCode.CATALOG_VALIDATION_ERROR,
                detail.isBlank() ? "Request validation failed" : detail);
        log.warn(VALIDATION_FAILED_LOG, detail);
        return ResponseEntity.status(body.getStatus()).body(body);
    }

    /** Build a {@code ProblemDetail} with the status from the code and the {@code code} attached. */
    private ProblemDetail problemDetail(CatalogErrorCode code, String detail) {
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
