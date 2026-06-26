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
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentStatus;
import com.gr74.payment.service.PaymentService;

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
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<ChargeResponse> charge(
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
