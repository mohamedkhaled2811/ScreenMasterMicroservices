package com.gr74.payment.webhook;

/**
 * What the webhook handler decided; only BAD_SIGNATURE is not a 200.
 */
public record WebhookResult(Outcome outcome, String detail) {

    public enum Outcome {
        /** The event was applied (or re-applied after a crash) — 200. */
        PROCESSED,
        /** An already-PROCESSED event redelivered — 200, no-op. */
        DUPLICATE,
        /** Stored but deliberately not applied (unknown session, uninteresting type, stale) — 200. */
        IGNORED,
        /** The signature did not verify — 400, evidence kept. */
        BAD_SIGNATURE
    }

    public static WebhookResult processed(Long attemptId) {
        return new WebhookResult(Outcome.PROCESSED,
                attemptId == null ? "applied" : "applied for attempt " + attemptId);
    }

    /** A late event for an already-terminal attempt — dropped by the guard, still a 200. */
    public static WebhookResult processedAsStale(Long attemptId) {
        return new WebhookResult(Outcome.PROCESSED, "stale event for terminal attempt " + attemptId);
    }

    public static WebhookResult duplicate() {
        return new WebhookResult(Outcome.DUPLICATE, "already processed");
    }

    public static WebhookResult ignored(String reason) {
        return new WebhookResult(Outcome.IGNORED, reason);
    }

    public static WebhookResult badSignature() {
        return new WebhookResult(Outcome.BAD_SIGNATURE, "signature invalid");
    }
}
