package com.gr74.catalog.exception;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OpenAPI-only documentation of the error body every failing endpoint returns.
 *
 * <p>Never instantiated or returned — the real error body is a Spring
 * {@link org.springframework.http.ProblemDetail} built in {@link GlobalExceptionHandler}. This mirror
 * exists so springdoc has a schema to render, because it cannot infer the custom {@code code} member
 * (added at runtime via {@code setProperty("code", …)}). Keep the {@code code} values in sync with
 * {@link CatalogErrorCode} — the coupling is manual (see {@code docs/concepts/openapi-springdoc.md}).
 *
 * @see CatalogErrorCode
 */
@Schema(name = "ProblemDetail", description = "RFC 9457 problem response (application/problem+json) with a stable machine-readable code.")
public record ApiError(

        @Schema(description = "A URI reference identifying the problem type.", example = "about:blank")
        String type,

        @Schema(description = "Short, human-readable summary of the problem type.", example = "Validation failed")
        String title,

        @Schema(description = "HTTP status code.", example = "400")
        int status,

        @Schema(description = "Human-readable explanation specific to this occurrence.", example = "minRating must be <= 10")
        String detail,

        @Schema(description = "A URI reference identifying the specific occurrence.", example = "/movies")
        String instance,

        @Schema(
                description = "Stable, machine-readable error code — the value clients branch on. One of CatalogErrorCode.",
                example = "CATALOG_VALIDATION_ERROR",
                allowableValues = {
                        "CATALOG_VALIDATION_ERROR",
                        "CATALOG_MOVIE_NOT_FOUND",
                        "CATALOG_TMDB_SYNC_ERROR",
                        "CATALOG_INTERNAL_ERROR"
                })
        String code) {
}
