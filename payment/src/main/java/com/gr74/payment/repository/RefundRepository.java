package com.gr74.payment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.payment.model.Refund;

/**
 * Spring Data repository for {@link Refund}.
 *
 * <p>{@code findByIdempotencyKey} is what makes the automatic compensation safe to redeliver: a
 * second {@code BookingConfirmationRejected} for the same booking finds the existing refund instead
 * of issuing another one.
 */
public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    List<Refund> findByPaymentIdOrderByCreatedAtAsc(Long paymentId);
}
