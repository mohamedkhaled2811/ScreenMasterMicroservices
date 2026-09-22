package com.gr74.payment.model;

/**
 * Gateways this service talks to; the key GatewayRegistry maps by. Stored as STRING.
 */
public enum PaymentGatewayType {

    /** Stripe test mode; hosted Checkout. */
    STRIPE,

    /** Paymob sandbox; hosted iframe checkout. */
    PAYMOB,

    /** Controllable in-house gateway with configurable failure rate and TTL. */
    SANDBOX
}
