package com.gr74.payment.exception;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OpenAPI-only documentation of the RFC 9457 error body; never instantiated by controllers.
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
                        "PAYMENT_BOOKING_NOT_FOUND",
                        "PAYMENT_FORBIDDEN",
                        "PAYMENT_BOOKING_NOT_PAYABLE",
                        "PAYMENT_BOOKING_EXPIRED",
                        "PAYMENT_ALREADY_PAID",
                        "PAYMENT_GATEWAY_NOT_AVAILABLE",
                        "PAYMENT_CURRENCY_NOT_SUPPORTED",
                        "PAYMENT_GATEWAY_UNAVAILABLE",
                        "PAYMENT_BOOKING_SERVICE_UNAVAILABLE",
                        "PAYMENT_WEBHOOK_SIGNATURE_INVALID",
                        "PAYMENT_REFUND_EXCEEDS_REMAINING",
                        "PAYMENT_NOT_REFUNDABLE",
                        "PAYMENT_INTERNAL_ERROR"
                })
        String code) {
}
