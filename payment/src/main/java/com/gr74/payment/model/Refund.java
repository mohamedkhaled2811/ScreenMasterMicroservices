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
 * One refund against a payment. Status is derived into the payment total only once SUCCEEDED via webhook.
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

    /** Gateway refund id; null until the gateway answers. */
    @Column(name = "gateway_refund_id", length = 255)
    private String gatewayRefundId;

    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING) // never ordinal
    @Column(nullable = false, length = 24)
    private RefundStatus status;

    /** Why this refund exists, e.g. {@code BOOKING_EXPIRED}. */
    @Column(nullable = false, updatable = false, length = 255)
    private String reason;

    /** UNIQUE guard against duplicate refunds. */
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

    /** Record gateway acceptance; stays PENDING until the webhook confirms. */
    public void recordAcceptance(String gatewayRefundId) {
        this.gatewayRefundId = gatewayRefundId;
        touch();
    }

    /** Confirm from webhook; no-op if already terminal so redelivery is safe. */
    public boolean markSucceeded(String gatewayRefundId) {
        if (status != RefundStatus.PENDING) {
            return false;
        }
        this.status = RefundStatus.SUCCEEDED;
        this.gatewayRefundId = gatewayRefundId;
        touch();
        return true;
    }

    /** Mark rejected; contributes nothing to the refunded total. */
    public boolean markFailed() {
        if (status != RefundStatus.PENDING) {
            return false;
        }
        this.status = RefundStatus.FAILED;
        touch();
        return true;
    }

    /** Set by {@link Payment#addRefund}. */
    void assignTo(Payment payment) {
        this.payment = payment;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
