package com.gr74.payment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gr74.payment.dto.ChargeRequest;
import com.gr74.payment.dto.ChargeResponse;
import com.gr74.payment.exception.ApiError;
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentStatus;
import com.gr74.payment.service.PaymentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The fake provider's one endpoint.
 *
 * <p>{@code POST /payments} with an {@code Idempotency-Key} header charges the amount and maps the
 * outcome to HTTP: <b>approved → 200</b>, <b>declined → 402 Payment Required</b>. Retrying with the
 * same key replays the original outcome (and the same status code) — the idempotency guarantee
 * lives in {@link PaymentService}; this class only translates between HTTP and the service.
 *
 * <p>Error outcomes (bad input, provider down, anything unexpected) are <em>not</em> handled here:
 * they are thrown and translated to an RFC 9457 {@code ProblemDetail} with a stable {@code code} by
 * {@code com.gr74.payment.exception.GlobalExceptionHandler}, so this method only describes the happy
 * path and the expected business decline.
 *
 * <p>DTOs cross the wire, not the {@link Payment} entity (project convention,
 * {@code docs/concepts/spring-web-annotations.md}).
 */
@Slf4j
@Validated // enforces @NotBlank on the @RequestHeader param (method-level constraint)
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Charge a payment through the fake, idempotent provider.")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @Operation(
            summary = "Charge a payment",
            description = """
                    Charges the amount and returns the outcome. Requires an Idempotency-Key header:
                    retrying with the same key replays the original outcome (and status) without charging
                    twice. Approved -> 200; declined -> 402 (a normal business outcome, not an error).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Charge approved.",
                    content = @Content(schema = @Schema(implementation = ChargeResponse.class))),
            @ApiResponse(responseCode = "402", description = "Charge declined — a normal business outcome; the body is a ChargeResponse with status=DECLINED, not an error.",
                    content = @Content(schema = @Schema(implementation = ChargeResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed — missing Idempotency-Key header, or a non-positive / malformed amount. code = PAYMENT_VALIDATION_ERROR.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "The downstream provider could not be reached. code = PAYMENT_PROVIDER_UNAVAILABLE.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<ChargeResponse> charge(
            @Parameter(description = "Idempotency key — reuse the same value to replay a prior outcome.", required = true, example = "booking-42-attempt-1")
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody ChargeRequest request) {

        Payment payment = paymentService.charge(idempotencyKey, request.amount());
        ChargeResponse body = ChargeResponse.from(payment);

        // Declined is a normal, expected business outcome — not a server error. 402 lets callers
        // (Booking's saga) branch on it cleanly without parsing a 200 body for failure.
        HttpStatus status = payment.getStatus() == PaymentStatus.APPROVED
                ? HttpStatus.OK
                : HttpStatus.PAYMENT_REQUIRED;

        log.info("POST /payments idempotencyKey={} -> {} ({})", idempotencyKey, payment.getStatus(), status.value());
        return ResponseEntity.status(status).body(body);
    }
}
