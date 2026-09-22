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
 * Sandbox gateway's own ledger of hosted checkouts. Only the sandbox gateway touches it;
 * fetchStatus reports the recorded outcome, never a fresh draw.
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

    /** Checkout session this outcome belongs to. */
    @Column(name = "session_id", nullable = false, updatable = false, length = 255)
    private String sessionId;

    /** Sandbox payment id minted at pay time. */
    @Column(name = "gateway_payment_id", updatable = false, length = 255)
    private String gatewayPaymentId;

    /** Outcome drawn once at pay time; never re-drawn. */
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
