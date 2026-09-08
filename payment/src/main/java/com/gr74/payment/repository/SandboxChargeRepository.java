package com.gr74.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.payment.model.SandboxCharge;

/**
 * Spring Data repository for {@link SandboxCharge}.
 *
 * <p>Used only by the sandbox gateway ({@code fetchStatus}) and its checkout controller (recording
 * the drawn outcome). Payment domain code never touches this — it is the third party's ledger,
 * parked in our database for the lab.
 */
public interface SandboxChargeRepository extends JpaRepository<SandboxCharge, Long> {

    Optional<SandboxCharge> findBySessionId(String sessionId);

    Optional<SandboxCharge> findByGatewayPaymentId(String gatewayPaymentId);
}
