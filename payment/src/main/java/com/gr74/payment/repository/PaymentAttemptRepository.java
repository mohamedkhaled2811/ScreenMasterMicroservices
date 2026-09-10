package com.gr74.payment.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;

/** Spring Data repository for {@link PaymentAttempt}. */
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    /**
     * The webhook path's lookup: which attempt is this delivery about? Scoped by gateway because two
     * gateways could in principle mint the same session string.
     */
    Optional<PaymentAttempt> findByGatewayAndGatewaySessionId(PaymentGatewayType gateway, String sessionId);

    /**
     * Lookup by the gateway's transaction id — used only for refund correlation fallback (a Paymob
     * "original transaction reports refunded" callback names the transaction but no refund id).
     * Never used for payment outcomes: those correlate by session id.
     */
    Optional<PaymentAttempt> findByGatewayAndGatewayPaymentId(PaymentGatewayType gateway, String gatewayPaymentId);

    /**
     * The live attempt for a payment, if any — what "Pay Again" reuses instead of opening a second
     * session. At most one can exist (partial unique index {@code uq_active_attempt_per_payment}).
     */
    Optional<PaymentAttempt> findByPaymentIdAndStatus(Long paymentId, PaymentAttemptStatus status);

    List<PaymentAttempt> findByPaymentIdOrderByCreatedAtAsc(Long paymentId);

    /**
     * Attempts whose gateway session has lapsed — the expiry sweeper's input. Note this is the
     * SESSION clock, not the booking hold: sweeping these only closes dead sessions and never
     * touches a booking.
     */
    @Query("""
            select a from PaymentAttempt a
             where a.status = :status
               and a.expiresAt is not null
               and a.expiresAt < :now
            """)
    List<PaymentAttempt> findLapsed(@Param("status") PaymentAttemptStatus status, @Param("now") Instant now);

    /**
     * Attempts stuck PENDING past a cutoff — reconciliation's input. These are the ones whose
     * outcome we may simply never have been told, because we were down when the webhook fired.
     */
    @Query("""
            select a from PaymentAttempt a
             where a.status = :status
               and a.gatewaySessionId is not null
               and a.createdAt < :cutoff
            """)
    List<PaymentAttempt> findStale(@Param("status") PaymentAttemptStatus status, @Param("cutoff") Instant cutoff);
}
