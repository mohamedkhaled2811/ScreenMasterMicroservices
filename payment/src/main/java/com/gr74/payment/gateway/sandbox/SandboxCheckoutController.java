package com.gr74.payment.gateway.sandbox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.SandboxCharge;
import com.gr74.payment.repository.PaymentAttemptRepository;
import com.gr74.payment.repository.SandboxChargeRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sandbox gateway pay page (plain HTML): records the outcome in the sandbox ledger,
 * then delivers the signed webhook. Payment code learns outcomes only via webhook or fetchStatus.
 */
@Slf4j
@RestController
@RequestMapping("/payments/sandbox/checkout")
@RequiredArgsConstructor
@Tag(name = "Sandbox checkout", description = "The fake gateway's hosted pay page (lab surface, no auth).")
public class SandboxCheckoutController {

    /** Header the sandbox adapter verifies. */
    static final String SIGNATURE_HEADER = "x-sandbox-signature";

    private final PaymentAttemptRepository attempts;
    private final SandboxChargeRepository charges;
    private final SandboxGateway gateway;
    private final SandboxWebhookClient webhookClient;
    private final ObjectMapper objectMapper;

    /** Hosted page with Pay / Decline / pay-without-webhook buttons. */
    @GetMapping(path = "/{sessionId}", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Render the sandbox pay page",
            description = "Plain-HTML stand-in for a hosted gateway checkout form. No auth — lab surface.")
    public String checkoutPage(@PathVariable String sessionId) {
        PaymentAttempt attempt = requireAttempt(sessionId);
        String action = "/payments/sandbox/checkout/" + escape(sessionId) + "/pay";
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Sandbox checkout</title></head>
                <body>
                <h1>ScreenMaster sandbox checkout</h1>
                <p>Session <code>%s</code> — attempt %d, gateway %s.</p>
                <p>This page stands in for Stripe's hosted form. Paying records the outcome in the
                sandbox ledger and delivers a signed webhook; declining does the same for a failure.
                The third button records the outcome but withholds the webhook (a network partition),
                which reconciliation must recover.</p>
                <form method="post" action="%s"><input type="hidden" name="outcome" value="succeed">
                <button type="submit">Pay</button></form>
                <form method="post" action="%s"><input type="hidden" name="outcome" value="decline">
                <button type="submit">Decline</button></form>
                <form method="post" action="%s"><input type="hidden" name="deliverWebhook" value="false">
                <button type="submit">Pay, but drop the webhook (simulate partition)</button></form>
                </body></html>
                """.formatted(escape(sessionId), attempt.getId(), attempt.getGateway(), action, action, action);
    }

    /**
     * Record the checkout outcome and deliver the signed webhook.
     *
     * @param outcome        {@code succeed} or {@code decline}; omitted draws once via shouldSucceed()
     * @param deliverWebhook {@code false} records the outcome but withholds the webhook
     */
    @PostMapping(path = "/{sessionId}/pay", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Pay or decline a sandbox checkout",
            description = "Records the drawn outcome in the sandbox ledger, then delivers the signed "
                    + "webhook through the front door. deliverWebhook=false withholds the delivery.")
    public ResponseEntity<String> pay(
            @PathVariable String sessionId,
            @RequestParam(required = false) String outcome,
            @RequestParam(defaultValue = "true") boolean deliverWebhook) {

        requireAttempt(sessionId);
        boolean succeeded = resolveOutcome(outcome);
        SandboxCharge recorded = recordOnce(sessionId, succeeded);

        String payload = webhookPayload(recorded);
        boolean delivered = false;
        if (deliverWebhook) {
            delivered = webhookClient.deliver("sandbox", payload,
                    gateway.sign(payload), SIGNATURE_HEADER);
        } else {
            log.info("SANDBOX outcome recorded but webhook withheld sessionId={} (partition stand-in)",
                    sessionId);
        }

        String body = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Sandbox checkout result</title></head>
                <body><h1>%s</h1>
                <p>Session <code>%s</code> recorded as <b>%s</b>; webhook %s.</p>
                </body></html>
                """.formatted(delivered ? "Done — check your payment status"
                        : "Recorded" + (deliverWebhook ? " (delivery failed — reconciliation will recover it)"
                                : " (webhook withheld — reconciliation will recover it)"),
                escape(sessionId), recorded.getOutcome(),
                delivered ? "delivered" : "NOT delivered");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
    }

    private PaymentAttempt requireAttempt(String sessionId) {
        return attempts.findByGatewayAndGatewaySessionId(PaymentGatewayType.SANDBOX, sessionId)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND,
                        "No sandbox session " + sessionId));
    }

    private boolean resolveOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            // Drawn once and recorded; webhook and fetchStatus report the recorded answer.
            return gateway.shouldSucceed();
        }
        return switch (outcome.trim().toLowerCase()) {
            case "succeed" -> true;
            case "decline" -> false;
            default -> throw new PaymentException(PaymentErrorCode.PAYMENT_VALIDATION_ERROR,
                    "outcome must be 'succeed' or 'decline'");
        };
    }

    /** Record the drawn outcome idempotently; UNIQUE session constraint guards races. */
    private SandboxCharge recordOnce(String sessionId, boolean succeeded) {
        return charges.findBySessionId(sessionId).orElseGet(() -> {
            SandboxCharge charge = new SandboxCharge(sessionId, "sbx_pay_" + UUID.randomUUID(),
                    succeeded ? PaymentAttemptStatus.SUCCEEDED : PaymentAttemptStatus.FAILED,
                    succeeded ? null : "simulated_decline");
            try {
                return charges.saveAndFlush(charge);
            } catch (DataIntegrityViolationException race) {
                log.warn("Concurrent pay for sessionId={}; returning the winning record", sessionId);
                return charges.findBySessionId(sessionId).orElseThrow(() -> race);
            }
        });
    }

    private String webhookPayload(SandboxCharge charge) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", "evt_" + UUID.randomUUID());
            payload.put("type", charge.getOutcome() == PaymentAttemptStatus.SUCCEEDED
                    ? "payment.succeeded" : "payment.failed");
            payload.put("sessionId", charge.getSessionId());
            payload.put("paymentId", charge.getGatewayPaymentId());
            if (charge.getFailureReason() != null) {
                payload.put("failureReason", charge.getFailureReason());
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                    "Could not build sandbox webhook payload", e);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
