package com.gr74.payment.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Payment obligation for one booking, created once (UNIQUE booking_id) and settled via attempts.
 * bookingId/userId are plain cross-service ids; amount is snapshotted from Booking, never client-supplied.
 */
@Entity
@Table(
        name = "payments",
        uniqueConstraints = @UniqueConstraint(name = "uq_payments_booking_id", columnNames = "booking_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs it; nobody else should
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Cross-service id, no FK. */
    @Column(name = "booking_id", nullable = false, updatable = false)
    private Long bookingId;

    /** Cross-service id, no FK. */
    @Column(name = "user_id", nullable = false, updatable = false, length = 36)
    private String userId;

    /** Snapshotted from Booking at creation; never recomputed. */
    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** ISO-4217 code, e.g. {@code EGP}. Always stored with the amount. */
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING) // never ordinal
    @Column(nullable = false, length = 24)
    private PaymentStatus status;

    /** Running total of SUCCEEDED refunds; drives the refunded states. */
    @Column(name = "refunded_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal refundedAmount;

    /** Attempts against this payment; cascaded, owned by the payment. */
    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<PaymentAttempt> attempts = new ArrayList<>();

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<Refund> refunds = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Payment(Long bookingId, String userId, BigDecimal amount, String currency) {
        this.bookingId = bookingId;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.PENDING;
        this.refundedAmount = BigDecimal.ZERO;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Promote to PAID; no-op if already PAID so duplicate webhooks are safe. Returns true if changed. */
    public boolean markPaid() {
        if (status == PaymentStatus.PAID) {
            return false;
        }
        this.status = PaymentStatus.PAID;
        touch();
        return true;
    }

    /** Close as unpayable; no-op if already terminal. */
    public boolean markFailed() {
        if (status.isTerminal()) {
            return false;
        }
        this.status = PaymentStatus.FAILED;
        touch();
        return true;
    }

    /** Amount still refundable: amount less refunded total. */
    public BigDecimal remainingRefundable() {
        return amount.subtract(refundedAmount);
    }

    /** Add a webhook-confirmed refund to the total and re-derive status. */
    public void applyRefund(BigDecimal refundAmount) {
        this.refundedAmount = this.refundedAmount.add(refundAmount);
        // compareTo, not equals: 500.0 and 500.00 are equal in value but not as BigDecimals.
        this.status = this.refundedAmount.compareTo(this.amount) >= 0
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
        touch();
    }

    /** Attach an attempt, keeping both sides consistent. */
    public void addAttempt(PaymentAttempt attempt) {
        attempts.add(attempt);
        attempt.assignTo(this);
    }

    public void addRefund(Refund refund) {
        refunds.add(refund);
        refund.assignTo(this);
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
