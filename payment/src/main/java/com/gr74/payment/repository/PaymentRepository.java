package com.gr74.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.gr74.payment.model.Payment;

/**
 * Spring Data repository for {@link Payment}.
 *
 * <p>{@code findByBookingId} is the read half of "create-or-retrieve the obligation"; the
 * {@code UNIQUE booking_id} constraint is the write half that closes the concurrent-create race.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByBookingId(Long bookingId);

    /**
     * The payment with its attempts, fetched inside the caller's transaction. The webhook path runs
     * with {@code open-in-view: false}, so it may not traverse the attempt's lazy {@code payment}
     * proxy outside a session — it loads the payment (and its collections) here instead.
     */
    @Query("select p from Payment p left join fetch p.attempts where p.id = :id")
    Optional<Payment> findWithAttemptsById(@Param("id") Long id);

    /**
     * The payment row under {@code PESSIMISTIC_WRITE}: the refund invariant
     * ({@code SUM(SUCCEEDED + PENDING refunds) <= amount}) is checked and the PENDING refund
     * inserted while this lock is held, so two concurrent refunds serialize instead of both
     * passing the check. Never held across a network call — the gateway is called after this
     * transaction commits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") Long id);
}
