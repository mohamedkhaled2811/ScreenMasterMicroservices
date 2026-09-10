package com.gr74.payment.webhook;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gr74.payment.exception.ApiError;
import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.model.PaymentGatewayType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static java.util.Collections.list;

/**
 * Gateway webhooks — the ONLY path to {@code PAID}.
 *
 * <p>One endpoint per gateway, reading the <b>raw body bytes</b> ({@code @RequestBody byte[]}):
 * signatures are computed over exact bytes, and a Jackson round-trip (reordered keys, changed
 * whitespace) would break verification. This is not negotiable — a parsed DTO here would silently
 * reject every genuine delivery.
 *
 * <p>Reached through the front door as {@code /api/payments/webhooks/{gateway}} (the existing
 * {@code /api/payments/**} route already covers it). Gateways have no JWT, so this path is
 * authenticated by <b>signature verification</b> instead — a different mechanism proving the same
 * thing (Phase 7 permits the path for exactly this reason).
 *
 * <p>The signature itself does not always arrive in a header: Stripe and sandbox sign in one,
 * while Paymob appends {@code ?hmac=<sha512-hex>} to the callback URL. Both channels are folded
 * into a single lower-cased map by {@link #signalsFrom}, so adapters read one place.
 *
 * <p>Errors are thrown, never returned: {@code GlobalExceptionHandler} renders every one as an RFC
 * 9457 {@code ProblemDetail} with a stable {@code code}.
 */
@Slf4j
@RestController
@RequestMapping("/payments/webhooks")
@RequiredArgsConstructor
@Tag(name = "Payment webhooks", description = "Gateway callbacks — the only path to PAID. Signature-authenticated, not JWT.")
public class WebhookController {

    private final WebhookProcessor processor;

    /**
     * Receive one gateway delivery.
     *
     * <p>200 for processed, duplicate, unknown session, or uninteresting type — a gateway retries
     * any non-2xx for hours, so 200 is the answer to almost everything. 400 only for a bad-or-missing
     * signature (stored with {@code signature_valid=false} first) or an unknown gateway segment.
     */
    @PostMapping(
            path = "/{gateway}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Receive a gateway webhook — the only path to PAID",
            description = """
                    Verifies the gateway signature over the raw body, stores the delivery as evidence, \
                    dedupes on UNIQUE (gateway, event_id), and applies the outcome. A redelivered event \
                    changes nothing; an unknown session or uninteresting type is stored and answered 200. \
                    Authenticated by HMAC signature — x-sandbox-signature / stripe-signature headers, \
                    or Paymob's ?hmac= query parameter — no JWT by design.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Processed, duplicate, unknown session, or uninteresting event type.",
                    content = @Content(schema = @Schema(implementation = WebhookAck.class))),
            @ApiResponse(responseCode = "400",
                    description = "PAYMENT_WEBHOOK_SIGNATURE_INVALID (bad or missing signature) or "
                            + "PAYMENT_VALIDATION_ERROR (unknown gateway path segment).",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<WebhookAck> receive(
            @Parameter(description = "stripe | paymob | sandbox — anything else is a coded 400.",
                    required = true)
            @PathVariable String gateway,
            @RequestBody byte[] rawBody,
            HttpServletRequest request) {

        PaymentGatewayType type = parseGateway(gateway);
        // Signature channel differs per gateway (header for Stripe/sandbox, query string for
        // Paymob) and containers preserve the sent casing — normalize both into one lower-cased
        // map here rather than in every adapter.
        Map<String, String> headers = signalsFrom(request);

        WebhookResult result = processor.process(type, rawBody, headers);
        if (result.outcome() == WebhookResult.Outcome.BAD_SIGNATURE) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_WEBHOOK_SIGNATURE_INVALID,
                    "Webhook signature verification failed for " + type);
        }
        log.info("Webhook gateway={} outcome={} detail={}", type, result.outcome(), result.detail());
        return ResponseEntity.ok(new WebhookAck(type.name().toLowerCase(Locale.ROOT),
                result.outcome().name().toLowerCase(Locale.ROOT), result.detail()));
    }

    private static PaymentGatewayType parseGateway(String segment) {
        if (segment == null) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_VALIDATION_ERROR,
                    "Gateway path segment is missing; expected stripe | paymob | sandbox");
        }
        try {
            return PaymentGatewayType.valueOf(segment.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_VALIDATION_ERROR,
                    "Unknown gateway '" + segment + "'; expected stripe | paymob | sandbox", e);
        }
    }

    /**
     * Every signature-bearing signal on the delivery, keyed lower-case: headers first, then query
     * parameters.
     *
     * <p>Gateways disagree on the channel. Stripe and sandbox sign in a header; <b>Paymob sends no
     * signature header at all</b> — its HMAC rides the callback query string
     * ({@code ?hmac=<sha512-hex>}). Merging both here once means no adapter has to know which
     * channel its gateway chose, and the port keeps its single map.
     *
     * <p>Headers win on collision ({@code putIfAbsent} after the header pass): a query parameter
     * must never be able to shadow a real signature header, or appending
     * {@code ?stripe-signature=...} to a callback URL would override the genuine one.
     *
     * <p>Reading {@code getParameterMap()} is safe on this POST: the endpoint consumes JSON and
     * binds the body as {@code byte[]}, so the container never form-parses the body into it.
     */
    private static Map<String, String> signalsFrom(HttpServletRequest request) {
        Map<String, String> signals = new TreeMap<>();
        for (String name : list(request.getHeaderNames())) {
            signals.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        request.getParameterMap().forEach((name, values) -> {
            if (values.length > 0) {
                signals.putIfAbsent(name.toLowerCase(Locale.ROOT), values[0]);
            }
        });
        return signals;
    }

    /** What a 200 to a gateway looks like — a small ack, never an entity. */
    public record WebhookAck(String gateway, String outcome, String detail) {
    }
}
