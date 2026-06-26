package com.gr74.payment.dto;

import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentStatus;

/**
 * Response for {@code POST /payments} — a DTO, not the {@link Payment} entity, so the JSON contract
 * stays decoupled from the persistence model (the project convention: DTOs cross the wire). Callers
 * (Booking, in Phase 3) key off {@code status}; {@code providerReference} is for reconciliation.
 */
public record ChargeResponse(String idempotencyKey, PaymentStatus status, String providerReference) {

    public static ChargeResponse from(Payment payment) {
        return new ChargeResponse(
                payment.getIdempotencyKey(),
                payment.getStatus(),
                payment.getProviderReference());
    }
}
