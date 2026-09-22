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

    /** Webhook lookup: which attempt is this delivery about (scoped by gateway). */
    Optional<PaymentAttempt> findByGatewayAndGatewaySessionId(PaymentGatewayType gateway, String sessionId);

    /** Lookup by gateway transaction id; refund-correlation fallback only. */
    Optional<PaymentAttempt> findByGatewayAndGatewayPaymentId(PaymentGatewayType gateway, String gatewayPaymentId);

    /** Live attempt for a payment, if any; reused by Pay Again. */
    Optional<PaymentAttempt> findByPaymentIdAndStatus(Long paymentId, PaymentAttemptStatus status);

    List<PaymentAttempt> findByPaymentIdOrderByCreatedAtAsc(Long paymentId);

    /** Attempts whose session lapsed; expiry sweeper input. */
    @Query("""
            select a from PaymentAttempt a
             where a.status = :status
               and a.expiresAt is not null
               and a.expiresAt < :now
            """)
    List<PaymentAttempt> findLapsed(@Param("status") PaymentAttemptStatus status, @Param("now") Instant now);

    /** Attempts stuck PENDING past a cutoff; reconciliation input. */
    @Query("""
            select a from PaymentAttempt a
             where a.status = :status
               and a.gatewaySessionId is not null
               and a.createdAt < :cutoff
            """)
    List<PaymentAttempt> findStale(@Param("status") PaymentAttemptStatus status, @Param("cutoff") Instant cutoff);
}
