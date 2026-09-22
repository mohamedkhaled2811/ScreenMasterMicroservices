package com.gr74.payment.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One attempt to settle a payment through a gateway checkout session.
 * At most one PENDING attempt per payment (partial unique index); idempotencyKey is forwarded to the gateway.
 */
@Entity
@Table(name = "payment_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning payment (LAZY). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING) // never ordinal
    @Column(nullable = false, length = 24)
    private PaymentGatewayType gateway;

    /** Gateway session id; null until the gateway answers. */
    @Column(name = "gateway_session_id", length = 255)
    private String gatewaySessionId;

    /** Gateway payment id; usually arrives with the webhook. */
    @Column(name = "gateway_payment_id", length = 255)
    private String gatewayPaymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PaymentAttemptStatus status;

    /** Normalized failure reason, never gateway vocabulary. */
    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /** Checkout redirect URL; null until the gateway answers. */
    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    /** Forwarded to the gateway so a retried create cannot double-charge. UNIQUE. */
    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 120)
    private String idempotencyKey;

    /** Session deadline; replaced with the real one once the gateway answers. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PaymentAttempt(PaymentGatewayType gateway, String idempotencyKey) {
        this(gateway, idempotencyKey, null);
    }

    /** Fresh attempt with a provisional deadline so an unanswered call cannot wedge the payment. */
    public PaymentAttempt(PaymentGatewayType gateway, String idempotencyKey, Instant provisionalExpiresAt) {
        this.gateway = gateway;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentAttemptStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.expiresAt = provisionalExpiresAt;
    }

    /** Record the gateway session details; row is committed before the gateway is called. */
    public void recordSession(String gatewaySessionId, String checkoutUrl, Instant expiresAt) {
        this.gatewaySessionId = gatewaySessionId;
        this.checkoutUrl = checkoutUrl;
        this.expiresAt = expiresAt;
        touch();
    }

    /** Apply a terminal outcome; no-op if already terminal so duplicate webhooks are safe. */
    public boolean transitionTo(PaymentAttemptStatus newStatus, String gatewayPaymentId, String failureReason) {
        if (this.status.isTerminal()) {
            return false;
        }
        this.status = newStatus;
        if (gatewayPaymentId != null) {
            this.gatewayPaymentId = gatewayPaymentId;
        }
        this.failureReason = failureReason;
        touch();
        return true;
    }

    /** True when still open with a usable checkout URL — i.e. reusable. */
    public boolean isLiveAt(Instant now) {
        return status == PaymentAttemptStatus.PENDING
                && checkoutUrl != null
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    /** True when PENDING with no session recorded: an unanswered call to retry on the same row. */
    public boolean isStranded() {
        return status == PaymentAttemptStatus.PENDING
                && gatewaySessionId == null
                && checkoutUrl == null;
    }

    /** Re-point a session-less attempt at another gateway before retrying. */
    public void adoptGateway(PaymentGatewayType gateway) {
        if (gatewaySessionId != null) {
            throw new IllegalStateException(
                    "Attempt " + id + " already has gateway session " + gatewaySessionId
                            + " and must not be re-pointed at " + gateway);
        }
        this.gateway = gateway;
        touch();
    }

    /** Set by {@link Payment#addAttempt}. */
    void assignTo(Payment payment) {
        this.payment = payment;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
