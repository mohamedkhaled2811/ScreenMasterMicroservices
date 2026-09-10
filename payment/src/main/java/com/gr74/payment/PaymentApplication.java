package com.gr74.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The payment service.
 *
 * <p>Owns payments, payment attempts, gateway sessions, refunds, and inbound webhooks. It integrates
 * <b>real gateways</b> — Stripe and Paymob, both in sandbox/test mode — behind a single
 * {@link com.gr74.payment.gateway.PaymentGateway} port, plus a controllable
 * {@link com.gr74.payment.gateway.sandbox.SandboxGateway} whose failure rate can be dialled in for
 * the resilience demos. Adding a gateway is a new adapter bean; nothing else changes.
 *
 * <p><b>It never writes to booking-db.</b> A verified webhook marks a payment {@code PAID}, and the
 * fact is published for Booking to act on — Booking alone decides whether its booking may still be
 * confirmed. See {@code docs/concepts/payment-gateway-integration.md}.
 *
 * <p>A Eureka <b>client</b>: the starter on the classpath auto-registers it on startup, no
 * enable-annotation needed. {@code @ConfigurationPropertiesScan} binds the {@code payment.*} and
 * {@code payment.gateway.*} property records. {@code @EnableScheduling} drives the session-expiry
 * sweeper and the reconciliation job.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
