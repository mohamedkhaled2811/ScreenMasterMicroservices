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
 * Receives gateway webhooks over raw body bytes; signature-authenticated, not JWT.
 * Answers 200 to almost everything; 400 only for bad signatures or unknown gateways.
 */
@Slf4j
@RestController
@RequestMapping("/payments/webhooks")
@RequiredArgsConstructor
@Tag(name = "Payment webhooks", description = "Gateway callbacks — the only path to PAID. Signature-authenticated, not JWT.")
public class WebhookController {

    private final WebhookProcessor processor;

    /**
     * Receives one gateway delivery; 200 for processed, duplicate, unknown, or uninteresting.
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
        // Normalize headers and query params into one lower-cased map; headers win on collision.
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

    /** Merges headers and query parameters into one lower-cased map; headers win on collision. */
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

    /** Small ack returned to the gateway; never an entity. */
    public record WebhookAck(String gateway, String outcome, String detail) {
    }
}
