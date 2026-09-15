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
 * One <b>attempt</b> to settle a {@link Payment} through one gateway checkout session.
 *
 * <p>The distinction from {@code Payment} is the heart of the payment model. A payment is the
 * obligation ("booking #1001 owes 300 EGP"); an attempt is one try at discharging it ("session
 * ABC123 at Paymob, valid until 20:05"). A user who abandons checkout and clicks "Pay Again" gets a
 * <em>new attempt</em> on the same payment — never a second payment — so there is always exactly one
 * row that answers "is this booking paid?" while the messy retry history lives here.
 *
 * <p><b>At most one attempt per payment may be {@link PaymentAttemptStatus#PENDING}</b>, enforced by
 * a partial unique index ({@code uq_active_attempt_per_payment}, changeset 002-6). That is what
 * stops two concurrent "Pay" clicks from opening two gateway sessions.
 *
 * <p>{@code expiresAt} is the <b>gateway session</b> deadline, deliberately a different clock from
 * the booking hold: a lapsed session is recoverable (open a new attempt), a lapsed booking is not
 * (the seats may already be resold). A freshly inserted attempt carries a <em>provisional</em>
 * deadline (see {@link #PaymentAttempt(PaymentGatewayType, String, Instant)}): until the gateway
 * answers, the row is evidence of an unanswered call, and the provisional deadline is what stops
 * an unanswered call from wedging its payment forever — the expiry sweeper collects it and the
 * payment becomes payable again.
 *
 * <p>{@code idempotencyKey} is forwarded to the gateway so a retried create — or one whose response
 * we never saw — cannot produce a second charge.
 */
@Entity
@Table(name = "payment_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * LAZY because the webhook path looks an attempt up by session id and usually needs only its
     * own fields; {@code open-in-view: false} keeps the fetch inside the service transaction.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING) // never ordinal
    @Column(nullable = false, length = 24)
    private PaymentGatewayType gateway;

    /** The gateway's checkout-session id. Null until the gateway answers; unique per gateway. */
    @Column(name = "gateway_session_id", length = 255)
    private String gatewaySessionId;

    /** The gateway's payment/transaction id. Typically arrives with the webhook, not the session. */
    @Column(name = "gateway_payment_id", length = 255)
    private String gatewayPaymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PaymentAttemptStatus status;

    /** Normalized by the adapter — never a gateway's raw vocabulary. */
    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /** Where the client redirects the user. Null until the gateway answers. */
    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    /** Sent to the gateway so a retry is not a second charge. UNIQUE across all attempts. */
    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 120)
    private String idempotencyKey;

    /** The SESSION deadline. Null until the gateway tells us when its session lapses. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PaymentAttempt(PaymentGatewayType gateway, String idempotencyKey) {
        this(gateway, idempotencyKey, null);
    }

    /**
     * A fresh attempt with a provisional session deadline.
     *
     * <p>Until the gateway answers, the row describes an <em>unanswered</em> call: no checkout URL,
     * no session id, and therefore not reusable as a live session (see {@link #isLiveAt}). The
     * provisional {@code expiresAt} is not a real session deadline — it is replaced by
     * {@link #recordSession} when the gateway answers — but it must be set, because the
     * attempt-expiry sweeper only sees attempts with a non-null deadline: without it, an
     * unanswered call would wedge its payment forever (no sweeper and no reconciliation query
     * matches a session-less row).
     */
    public PaymentAttempt(PaymentGatewayType gateway, String idempotencyKey, Instant provisionalExpiresAt) {
        this.gateway = gateway;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentAttemptStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.expiresAt = provisionalExpiresAt;
    }

    /**
     * Record what the gateway returned when the session was opened. Kept separate from the
     * constructor because the row is committed <em>before</em> the gateway is called: if that call
     * times out, the attempt row already exists as evidence for reconciliation to resolve, instead
     * of a charge we have no record of.
     */
    public void recordSession(String gatewaySessionId, String checkoutUrl, Instant expiresAt) {
        this.gatewaySessionId = gatewaySessionId;
        this.checkoutUrl = checkoutUrl;
        this.expiresAt = expiresAt;
        touch();
    }

    /**
     * Apply a terminal outcome. Returns {@code false} without changing anything if this attempt is
     * already terminal — the guard that makes duplicate and out-of-order webhook delivery safe (a
     * late {@code FAILED} must never overwrite a {@code SUCCEEDED}).
     */
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

    /** True when this attempt is still open and its session has not lapsed — i.e. reusable. */
    public boolean isLiveAt(Instant now) {
        return status == PaymentAttemptStatus.PENDING
                && checkoutUrl != null
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    /**
     * True when this attempt is an unanswered call: still {@code PENDING} but the gateway never
     * returned a session, so there is no checkout to reuse — only a row to <em>retry</em>. A retry
     * reuses this row (and its idempotency key, which is what makes re-calling the gateway safe)
     * instead of inserting a second {@code PENDING} attempt the one-live-attempt index would reject.
     *
     * <p>The checkout URL is the discriminator, not just the session id: a recorded-but-lapsed
     * session (Pay Again) also has no <em>live</em> checkout, but it HAS an answer and must flow to
     * a fresh attempt, never to a re-call. In production all three session fields are set together
     * by {@link #recordSession}, so either null implies the others — but the checkout check is
     * what keeps the Pay-Again path out of the retry path.
     */
    public boolean isStranded() {
        return status == PaymentAttemptStatus.PENDING
                && gatewaySessionId == null
                && checkoutUrl == null;
    }

    /**
     * Point a stranded attempt at a (possibly different) gateway before retrying it.
     *
     * <p>Only a session-less attempt may be re-pointed: once the gateway has answered, the row
     * describes a real session at a real gateway and rewriting it would corrupt the evidence.
     * Adopting before the call (rather than only on success) is what lets a retry switch gateways
     * mid-outage — e.g. Sandbox is down, the user picks Stripe — while still reusing the one
     * {@code PENDING} slot the partial unique index allows.
     */
    public void adoptGateway(PaymentGatewayType gateway) {
        if (gatewaySessionId != null) {
            throw new IllegalStateException(
                    "Attempt " + id + " already has gateway session " + gatewaySessionId
                            + " and must not be re-pointed at " + gateway);
        }
        this.gateway = gateway;
        touch();
    }

    /** Set by {@link Payment#addAttempt} to keep both sides of the relationship in sync. */
    void assignTo(Payment payment) {
        this.payment = payment;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
