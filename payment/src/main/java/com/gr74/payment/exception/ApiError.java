package com.gr74.payment.exception;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OpenAPI-only documentation of the error body every failing endpoint returns.
 *
 * <p>This class is never instantiated or returned by any controller — the real error body is a Spring
 * {@link org.springframework.http.ProblemDetail} built in {@link GlobalExceptionHandler}. It exists
 * purely so springdoc has a schema to render in Swagger UI, because springdoc <em>cannot</em> infer the
 * custom {@code code} member (added at runtime via {@code problemDetail.setProperty("code", …)}) — it
 * only sees the framework's {@code ProblemDetail} type, which has no such field. We mirror the RFC 9457
 * shape plus our {@code code} extension here so the machine-readable contract siblings and the frontend
 * branch on is visible in the docs.
 *
 * <p>Keep the {@code code} description in sync with {@link PaymentErrorCode} — the coupling is manual
 * (see {@code docs/concepts/openapi-springdoc.md}, "Gotchas").
 *
 * @see PaymentErrorCode
 */
@Schema(name = "ProblemDetail", description = "RFC 9457 problem response (application/problem+json) with a stable machine-readable code.")
public record ApiError(

        @Schema(description = "A URI reference identifying the problem type.", example = "about:blank")
        String type,

        @Schema(description = "Short, human-readable summary of the problem type.", example = "Validation failed")
        String title,

        @Schema(description = "HTTP status code.", example = "400")
        int status,

        @Schema(description = "Human-readable explanation specific to this occurrence.", example = "amount: must be greater than 0.00")
        String detail,

        @Schema(description = "A URI reference identifying the specific occurrence.", example = "/payments")
        String instance,

        @Schema(
                description = "Stable, machine-readable error code — the value clients branch on. One of PaymentErrorCode.",
                example = "PAYMENT_VALIDATION_ERROR",
                allowableValues = {
                        "PAYMENT_VALIDATION_ERROR",
                        "PAYMENT_NOT_FOUND",
                        "PAYMENT_PROVIDER_UNAVAILABLE",
                        "PAYMENT_INTERNAL_ERROR"
                })
        String code) {
}
