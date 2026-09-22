package com.gr74.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.payment.model.SandboxCharge;

/**
 * Spring Data repository for {@link SandboxCharge}. Only the sandbox gateway uses it.
 */
public interface SandboxChargeRepository extends JpaRepository<SandboxCharge, Long> {

    Optional<SandboxCharge> findBySessionId(String sessionId);

    Optional<SandboxCharge> findByGatewayPaymentId(String gatewayPaymentId);
}
