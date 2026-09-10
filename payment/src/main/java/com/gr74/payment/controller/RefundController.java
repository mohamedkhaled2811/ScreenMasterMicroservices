package com.gr74.payment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gr74.payment.dto.RefundRequest;
import com.gr74.payment.dto.RefundResponse;
import com.gr74.payment.exception.ApiError;
import com.gr74.payment.service.RefundService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin-initiated (partial) refunds.
 *
 * <p><b>Auth — a documented lab gap.</b> This endpoint is reachable with no authorization: the
 * existing {@code /api/payments/**} gateway route already proxies it, and no bespoke exclusion
 * filter is added (that would be edge config for a hole Phase 7 closes anyway). Phase 7 puts an
 * ADMIN role on this path; until then it is lab surface — do not expose the deployment publicly
 * without that gate.
 *
 * <p>A {@code 201} means the gateway <em>accepted</em> the refund, not that money moved: the
 * refund stays {@code PENDING} until its webhook confirms it (see {@link RefundResponse}).
 * Errors are thrown, never returned.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Tag(name = "Refunds", description = "Admin-initiated (partial) refunds. Lab surface: unauthenticated until Phase 7 adds the ADMIN role.")
public class RefundController {

    private final RefundService refundService;

    @PostMapping("/{paymentId}/refunds")
    @Operation(
            summary = "Request a refund against a captured payment",
            description = """
                    Records a refund (full remaining when `amount` is omitted) and asks the gateway \
                    that captured the money to return it. The refund stays PENDING until the \
                    gateway's refund webhook confirms it — a 201 is acceptance, not proof the money \
                    moved.

                    Idempotent: pass `Idempotency-Key` to make retries safe; the same key returns \
                    the same refund instead of refunding twice. \
                    Unauthenticated lab surface until Phase 7.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Refund recorded (PENDING until the gateway confirms).",
                    content = @Content(schema = @Schema(implementation = RefundResponse.class))),
            @ApiResponse(responseCode = "400", description = "PAYMENT_VALIDATION_ERROR — non-positive amount.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "PAYMENT_NOT_FOUND.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = """
                    PAYMENT_NOT_REFUNDABLE (payment was never captured), \
                    PAYMENT_REFUND_EXCEEDS_REMAINING (would refund more than remains).""",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<RefundResponse> requestRefund(
            @Parameter(description = "Which payment obligation to refund.", required = true)
            @PathVariable Long paymentId,
            @Valid @RequestBody(required = false) RefundRequest request,
            @Parameter(description = "De-duplicates retries: the same key returns the same refund. Minted server-side when omitted.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        RefundResponse response = refundService.requestRefund(
                paymentId,
                request == null ? null : request.amount(),
                request == null ? null : request.reason(),
                idempotencyKey);
        log.info("POST /payments/{}/refunds amount={} status={} key={}",
                paymentId, response.amount(), response.status(), response.idempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
