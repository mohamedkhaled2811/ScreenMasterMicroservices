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
 * The payment <b>obligation</b> for one booking: "booking #1001 owes 300.00 EGP".
 *
 * <p>This is the row that answers "is this booking paid?". It is created once per booking — the
 * {@code UNIQUE booking_id} constraint makes "create-or-retrieve" race-free — and it survives any
 * number of failed {@link PaymentAttempt}s. A user who abandons checkout and returns gets a new
 * attempt on <em>this same</em> payment, never a second obligation. That split is what makes "Pay
 * Again" safe; see {@code docs/concepts/payment-gateway-integration.md}.
 *
 * <p>{@code bookingId} and {@code userId} are <b>cross-service references</b> (schema §8.2): plain
 * columns with no FK, because Booking owns bookings and Identity owns users. The amount is
 * <em>snapshotted from Booking</em> at creation and never recomputed — and never taken from the
 * client.
 *
 * <p>Money is {@link BigDecimal} plus an ISO-4217 {@code currency}, never a {@code double} and never
 * an amount without its currency. Enums are {@code STRING} (never ordinal — §2.4 fix). Schema owned
 * by Liquibase ({@code ddl-auto=validate}); must match {@code 002-rebuild-payment-domain.yaml}.
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

    /** Cross-service reference to Booking. No FK — Booking owns the booking row. */
    @Column(name = "booking_id", nullable = false, updatable = false)
    private Long bookingId;

    /** Cross-service reference to Identity (the Keycloak {@code sub} from Phase 7). */
    @Column(name = "user_id", nullable = false, updatable = false, length = 36)
    private String userId;

    /** Snapshotted from Booking at creation. Never recomputed, never client-supplied. */
    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** ISO-4217 code, e.g. {@code EGP} or {@code USD}. Always travels with the amount. */
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING) // never ordinal
    @Column(nullable = false, length = 24)
    private PaymentStatus status;

    /** Running total of {@link RefundStatus#SUCCEEDED} refunds. Drives the two refunded states. */
    @Column(name = "refunded_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal refundedAmount;

    /**
     * Every attempt made against this obligation. Cascaded because an attempt has no life of its
     * own outside its payment.
     */
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

    /**
     * Promote to {@link PaymentStatus#PAID} because an attempt succeeded.
     *
     * <p>Idempotent by design: calling it on an already-{@code PAID} payment is a no-op, because a
     * gateway may deliver the same webhook many times and reconciliation may race with it. Returns
     * whether this call actually changed anything, so the caller can decide whether to publish an
     * event — publishing twice would confirm a booking twice.
     */
    public boolean markPaid() {
        if (status == PaymentStatus.PAID) {
            return false;
        }
        this.status = PaymentStatus.PAID;
        touch();
        return true;
    }

    /** Close the obligation as unpayable (e.g. its booking expired before anything succeeded). */
    public boolean markFailed() {
        if (status.isTerminal()) {
            return false;
        }
        this.status = PaymentStatus.FAILED;
        touch();
        return true;
    }

    /** What may still be refunded: the amount less everything already refunded. */
    public BigDecimal remainingRefundable() {
        return amount.subtract(refundedAmount);
    }

    /**
     * Add a confirmed refund to the running total and re-derive the status from it. Called only
     * once a refund's own gateway webhook confirms it — a requested refund has moved no money.
     */
    public void applyRefund(BigDecimal refundAmount) {
        this.refundedAmount = this.refundedAmount.add(refundAmount);
        // compareTo, not equals: 500.0 and 500.00 are equal in value but not as BigDecimals.
        this.status = this.refundedAmount.compareTo(this.amount) >= 0
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
        touch();
    }

    /** Attach an attempt, keeping both sides of the relationship consistent. */
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
