package com.gr74.payment.provider;

import com.gr74.payment.model.PaymentStatus;

/**
 * What a provider returns from a charge attempt: the outcome plus the provider-side reference
 * (a real gateway returns its own transaction id; the fake provider mints a synthetic one). The
 * service persists this alongside the idempotency key so a replay can return the same reference.
 */
public record ProviderResult(PaymentStatus status, String providerReference) {

    public boolean isApproved() {
        return status == PaymentStatus.APPROVED;
    }
}
