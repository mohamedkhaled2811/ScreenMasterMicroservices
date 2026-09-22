package com.gr74.payment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.payment.model.Refund;

/**
 * Spring Data repository for {@link Refund}.
 */
public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    /** Refund-webhook correlation by gateway refund id. */
    Optional<Refund> findByGatewayRefundId(String gatewayRefundId);

    List<Refund> findByPaymentIdOrderByCreatedAtAsc(Long paymentId);
}
