package com.gr74.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.gr74.payment.model.Payment;

/** Spring Data repository for {@link Payment}. */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByBookingId(Long bookingId);

    /** Payment with attempts fetched in the caller's transaction. */
    @Query("select p from Payment p left join fetch p.attempts where p.id = :id")
    Optional<Payment> findWithAttemptsById(@Param("id") Long id);

    /** Payment row under PESSIMISTIC_WRITE; serializes concurrent refunds. Never held across I/O. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") Long id);
}
