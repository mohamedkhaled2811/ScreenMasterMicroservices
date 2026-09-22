package com.gr74.booking.exception;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OpenAPI-only documentation of the error body; never instantiated or returned.
 * The real body is a Spring ProblemDetail with a custom {@code code} added at runtime.
 *
 * @see BookingErrorCode
 */
@Schema(name = "ProblemDetail", description = "RFC 9457 problem response (application/problem+json) with a stable machine-readable code.")
public record ApiError(

        @Schema(description = "A URI reference identifying the problem type.", example = "about:blank")
        String type,

        @Schema(description = "Short, human-readable summary of the problem type.", example = "Theater not found")
        String title,

        @Schema(description = "HTTP status code.", example = "404")
        int status,

        @Schema(description = "Human-readable explanation specific to this occurrence.", example = "No theater with id 99")
        String detail,

        @Schema(description = "A URI reference identifying the specific occurrence.", example = "/theaters/99/screens")
        String instance,

        @Schema(
                description = "Stable, machine-readable error code — the value clients branch on. One of BookingErrorCode.",
                example = "BOOKING_THEATER_NOT_FOUND",
                allowableValues = {
                        "BOOKING_VALIDATION_ERROR",
                        "BOOKING_THEATER_NOT_FOUND",
                        "BOOKING_SCREEN_NOT_FOUND",
                        "BOOKING_SEAT_NOT_FOUND",
                        "BOOKING_SEAT_TYPE_NOT_FOUND",
                        "BOOKING_SHOWTIME_NOT_FOUND",
                        "BOOKING_MOVIE_NOT_FOUND",
                        "BOOKING_CATALOG_UNAVAILABLE",
                        "BOOKING_DUPLICATE",
                        "BOOKING_INTERNAL_ERROR"
                })
        String code) {
}
