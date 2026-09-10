package com.gr74.payment.model;

/**
 * The gateways this service can talk to — the key the {@code GatewayRegistry} maps by.
 *
 * <p>Adding a gateway is a new constant here plus a {@code @Component} implementing
 * {@code PaymentGateway}; no existing code changes. That add-only property is the entire point of
 * the port/registry pair (see {@code docs/concepts/payment-gateway-integration.md}).
 *
 * <p>Persisted as a {@code String} on both {@code payment_attempts} and {@code webhook_events}.
 */
public enum PaymentGatewayType {

    /** Stripe, in test mode. Hosted Checkout; settles USD in this project. */
    STRIPE,

    /** Paymob, sandbox. Hosted iframe checkout; settles EGP in integer piastres. */
    PAYMOB,

    /**
     * Our own controllable adapter — a production-shaped gateway we happen to own. It signs its own
     * webhooks and honours a configured failure rate and session TTL, which is what makes the
     * failure script (3.7) and the circuit-breaker demo (Phase 5) possible without hammering a real
     * sandbox. It is <em>not</em> a test mock: it implements the same port and is registered the
     * same way.
     */
    SANDBOX
}
