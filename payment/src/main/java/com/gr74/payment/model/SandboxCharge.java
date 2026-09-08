package com.gr74.payment.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The sandbox gateway's <b>own ledger</b> — what it remembers about each checkout it hosted.
 *
 * <p>This table belongs to the fake third party, not to Payment: it merely happens to be parked in
 * payment-db for this lab (a separate sandbox-gateway container with its own database would be
 * truer to life, but is scope-heavy for the lesson). <b>Payment domain code must never read it</b> —
 * only the sandbox gateway and its checkout controller touch it. Payment learns outcomes the way it
 * learns everything: through signed webhooks, or by asking the gateway (which consults this table).
 *
 * <p>Why it exists: {@code fetchStatus} must answer <em>the recorded outcome</em>, not roll dice —
 * reconciliation decides real money from that answer, and a random draw would confirm bookings that
 * were never paid for. Recording once at pay time also makes the answer survive a restart, which is
 * exactly the recovery the chaos demo (3.7) exercises.
 */
@Entity
@Table(
        name = "sandbox_charges",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_sandbox_charges_session_id",
                columnNames = "session_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SandboxCharge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The checkout session this outcome belongs to — the lookup key. */
    @Column(name = "session_id", nullable = false, updatable = false, length = 255)
    private String sessionId;

    /** The sandbox's own payment id, minted at pay time. What webhooks and fetchStatus report. */
    @Column(name = "gateway_payment_id", updatable = false, length = 255)
    private String gatewayPaymentId;

    /** The outcome drawn once at pay time via {@code shouldSucceed()} — never re-drawn. */
    @Enumerated(EnumType.STRING) // never ordinal
    @Column(nullable = false, updatable = false, length = 24)
    private PaymentAttemptStatus outcome;

    @Column(name = "failure_reason", updatable = false, length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SandboxCharge(String sessionId, String gatewayPaymentId,
            PaymentAttemptStatus outcome, String failureReason) {
        this.sessionId = sessionId;
        this.gatewayPaymentId = gatewayPaymentId;
        this.outcome = outcome;
        this.failureReason = failureReason;
        this.createdAt = Instant.now();
    }
}
