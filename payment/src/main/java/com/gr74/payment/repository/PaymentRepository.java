package com.gr74.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.payment.model.Payment;

/**
 * Spring Data repository for {@link Payment}.
 *
 * <p>{@code findByBookingId} is the read half of "create-or-retrieve the obligation"; the
 * {@code UNIQUE booking_id} constraint is the write half that closes the concurrent-create race.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByBookingId(Long bookingId);
}
