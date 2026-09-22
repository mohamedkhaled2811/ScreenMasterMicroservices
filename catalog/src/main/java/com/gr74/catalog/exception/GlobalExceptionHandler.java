package com.gr74.catalog.exception;

import java.util.stream.Collectors;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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
 * Renders every error as an RFC 9457 {@code ProblemDetail} with a stable {@code code}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Custom {@code ProblemDetail} member that holds the {@link CatalogErrorCode} name. */
    private static final String CODE_PROPERTY = "code";

    /** Shared log template for every validation-failure path (keeps the log message consistent). */
    private static final String VALIDATION_FAILED_LOG = "Validation failed: {}";

    /**
     * Any deliberately raised error (e.g. {@link MovieNotFoundException}).
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

    /** A path variable / request param with an invalid type (e.g. {@code GET /movies/abc}). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = "Parameter '" + ex.getName() + "' has an invalid value: " + ex.getValue();
        log.warn(VALIDATION_FAILED_LOG, detail);
        return problemDetail(CatalogErrorCode.CATALOG_VALIDATION_ERROR, detail);
    }

    /** A method-security denial (e.g. missing role). Rendered as 403, not 500. */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ProblemDetail handleAuthorizationDenied(AuthorizationDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return problemDetail(CatalogErrorCode.CATALOG_FORBIDDEN,
                "Insufficient permissions for this resource");
    }

    /** Fallback: anything unanticipated becomes a 500 without leaking internals. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception in catalog service", ex);
        return problemDetail(CatalogErrorCode.CATALOG_INTERNAL_ERROR,
                "An unexpected error occurred");
    }

    /** Bean-validation failures on {@code @Valid @RequestBody}. */
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

    /** Method-level constraint failures on controller parameters. */
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

    /** Build a {@code ProblemDetail} with the status from the code. */
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
