package com.gr74.payment.model;

/**
 * Outcome of a charge attempt.
 *
 * <p>Persisted as a {@code String} (never an ordinal — see the project convention and
 * {@code docs/concepts/jpa-and-hibernate.md}): ordinals break the moment someone reorders the
 * enum constants, silently remapping existing rows.
 */
public enum PaymentStatus {
    APPROVED,
    DECLINED
}
