package com.gr74.payment.model;

import java.math.BigDecimal;
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
 * One refund against a {@link Payment}.
 *
 * <p>A refund is a <b>row, not a status flip</b>, because partial refunds are real: a 1000 EGP
 * payment refunded 300 and then 200 leaves 500 refundable, which {@code PAID -> REFUNDED} cannot
 * express. The payment's status is <em>derived</em> from the running total of confirmed refunds
 * ({@link Payment#applyRefund}), so it can never contradict the rows.
 *
 * <p>The invariant {@code SUM(amount) <= payment.amount} is enforced in the service under a row
 * lock, not by a constraint — a CHECK cannot span rows.
 *
 * <p>A refund only becomes {@link RefundStatus#SUCCEEDED} when the gateway's own refund webhook
 * confirms it. The API call returning is not proof the money moved.
 */
@Entity
@Table(name = "refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    /** The gateway's refund id. Null until the gateway answers. */
    @Column(name = "gateway_refund_id", length = 255)
    private String gatewayRefundId;

    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING) // never ordinal
    @Column(nullable = false, length = 24)
    private RefundStatus status;

    /** Why this refund exists, e.g. {@code BOOKING_EXPIRED} for the automatic compensation path. */
    @Column(nullable = false, updatable = false, length = 255)
    private String reason;

    /** UNIQUE — the guard that stops the same compensation refunding twice. */
    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Refund(BigDecimal amount, String reason, String idempotencyKey) {
        this.amount = amount;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
        this.status = RefundStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Confirm this refund from its gateway webhook. Returns {@code false} if it is already terminal,
     * so a redelivered refund webhook cannot add the same amount to the payment's total twice.
     */
    public boolean markSucceeded(String gatewayRefundId) {
        if (status != RefundStatus.PENDING) {
            return false;
        }
        this.status = RefundStatus.SUCCEEDED;
        this.gatewayRefundId = gatewayRefundId;
        touch();
        return true;
    }

    /** The gateway rejected it. Contributes nothing to the payment's refunded total. */
    public boolean markFailed() {
        if (status != RefundStatus.PENDING) {
            return false;
        }
        this.status = RefundStatus.FAILED;
        touch();
        return true;
    }

    /** Set by {@link Payment#addRefund} to keep both sides of the relationship in sync. */
    void assignTo(Payment payment) {
        this.payment = payment;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
