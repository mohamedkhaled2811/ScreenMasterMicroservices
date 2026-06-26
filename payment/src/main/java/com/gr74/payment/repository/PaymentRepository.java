package com.gr74.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.payment.model.Payment;

/**
 * Spring Data repository for {@link Payment}. {@code findByIdempotencyKey} is the read half of the
 * idempotency guard: before charging, we look the key up here; the {@code UNIQUE} constraint is the
 * write half that closes the concurrent-duplicate race.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
