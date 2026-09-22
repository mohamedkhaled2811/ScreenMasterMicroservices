package com.gr74.payment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gr74.payment.dto.AvailableGatewaysResponse;
import com.gr74.payment.dto.CreatePaymentRequest;
import com.gr74.payment.dto.PaymentResponse;
import com.gr74.payment.dto.PaymentSessionResponse;
import com.gr74.payment.exception.ApiError;
import com.gr74.payment.gateway.GatewayRegistry;
import com.gr74.payment.security.CurrentUser;
import com.gr74.payment.service.PaymentService;
import com.gr74.payment.service.PaymentService.SessionOutcome;

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
 * Payment API: create checkout sessions and read payment state.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Create checkout sessions and read payment state.")
public class PaymentController {

    private final PaymentService paymentService;
    private final GatewayRegistry gatewayRegistry;

    /**
     * Create or reuse a checkout session for a booking; also serves Pay Again on the same payment.
     */
    @PostMapping
    @Operation(
            summary = "Create or reuse a checkout session",
            description = """
                    Validates the booking (exists, yours, PENDING, hold not lapsed, not already paid) and \
                    that the chosen gateway can settle its currency, then returns a checkout URL to \
                    redirect the user to.

                    This is also the "Pay Again" endpoint: if a live session already exists it is reused \
                    (200); if the previous attempt lapsed or failed, a new attempt is created on the same \
                    payment (201). A booking is never charged twice, and a lapsed session never costs the \
                    user their seats.

                    The amount is NOT accepted from the client — it is read from the Booking service.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "A new attempt and gateway session were created.",
                    content = @Content(schema = @Schema(implementation = PaymentSessionResponse.class))),
            @ApiResponse(responseCode = "200", description = "An existing live session was reused.",
                    content = @Content(schema = @Schema(implementation = PaymentSessionResponse.class))),
            @ApiResponse(responseCode = "400", description = """
                    PAYMENT_VALIDATION_ERROR (bad body or missing header), \
                    PAYMENT_GATEWAY_NOT_AVAILABLE (gateway not configured here), \
                    PAYMENT_CURRENCY_NOT_SUPPORTED (gateway cannot settle this currency).""",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "PAYMENT_FORBIDDEN — the booking belongs to another user.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "PAYMENT_BOOKING_NOT_FOUND — no such booking.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = """
                    PAYMENT_BOOKING_NOT_PAYABLE (wrong state), \
                    PAYMENT_BOOKING_EXPIRED (seat hold lapsed — create a new booking), \
                    PAYMENT_ALREADY_PAID.""",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = """
                    PAYMENT_GATEWAY_UNAVAILABLE (gateway unreachable; the attempt is left for reconciliation), \
                    PAYMENT_BOOKING_SERVICE_UNAVAILABLE (could not verify the amount — we fail closed).""",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<PaymentSessionResponse> createSession(
            @Parameter(description = "The acting user, from the verified JWT sub.", required = true)
            @CurrentUser String userId,
            @Valid @RequestBody CreatePaymentRequest request) {

        SessionOutcome outcome = paymentService.createSession(request, userId);

        log.info("POST /payments bookingId={} gateway={} -> paymentId={} attemptId={} ({})",
                request.bookingId(), request.gateway(),
                outcome.session().paymentId(), outcome.session().attemptId(),
                outcome.created() ? "created" : "reused");

        // 201 for a new attempt, 200 for a reused one — so a client can tell whether it just caused a
        // new gateway session or was handed the one it already had.
        return ResponseEntity
                .status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.session());
    }

    /**
     * Gateways usable in this deployment, optionally filtered by the booking's currency.
     */
    @GetMapping("/gateways")
    @Operation(
            summary = "List usable payment gateways",
            description = """
                    Returns the gateways registered in this deployment. A gateway with no configured \
                    credentials is absent entirely, never listed-but-broken. Supply `currency` to \
                    filter to gateways that can actually settle it.""")
    @ApiResponse(responseCode = "200", description = "The usable gateways.",
            content = @Content(schema = @Schema(implementation = AvailableGatewaysResponse.class)))
    public AvailableGatewaysResponse gateways(
            @Parameter(description = "ISO-4217 code, e.g. EGP or USD. Omit for all registered gateways.")
            @RequestParam(required = false) String currency) {

        return currency == null || currency.isBlank()
                ? new AvailableGatewaysResponse(null, gatewayRegistry.available())
                : new AvailableGatewaysResponse(currency.toUpperCase(),
                        gatewayRegistry.availableFor(currency.toUpperCase()));
    }

    /** Read one payment and its attempt history. */
    @GetMapping("/{paymentId}")
    @Operation(summary = "Read a payment",
            description = "Returns the payment with every attempt made against it, oldest first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The payment.",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "403", description = "PAYMENT_FORBIDDEN — not your payment.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "PAYMENT_NOT_FOUND.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    })
    public PaymentResponse findById(
            @CurrentUser String userId,
            @PathVariable Long paymentId) {
        return paymentService.findById(paymentId, userId);
    }

    /**
     * Read the payment for a booking (poll target after the checkout redirect; PENDING is normal briefly).
     */
    @GetMapping("/by-booking/{bookingId}")
    @Operation(summary = "Read the payment for a booking",
            description = """
                    Returns the payment covering a booking. Use this to poll after the user returns from \
                    the gateway — but note that PENDING is a normal answer for a moment, because the \
                    authoritative confirmation is the gateway's webhook, not the redirect.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The payment.",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "403", description = "PAYMENT_FORBIDDEN — not your booking.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "PAYMENT_NOT_FOUND — no payment for that booking yet.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    })
    public PaymentResponse findByBooking(
            @CurrentUser String userId,
            @PathVariable Long bookingId) {
        return paymentService.findByBookingId(bookingId, userId);
    }
}
