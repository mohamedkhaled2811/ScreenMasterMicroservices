package com.gr74.payment.model;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A persisted charge attempt. One row per <em>distinct</em> idempotency key — the
 * {@code idempotency_key} column is {@code UNIQUE} (see the Liquibase changeset), which is the
 * real guard against double-charging on a retried {@code POST /payments}: even two concurrent
 * requests with the same key can only ever insert one row.
 *
 * <p>The schema is owned by Liquibase, not Hibernate ({@code ddl-auto=validate}); this entity must
 * stay in sync with {@code db/changelog} or startup fails — which is the point.
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs a no-arg ctor; nobody else should use it
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING) // never ordinal — see project convention
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "provider_reference", nullable = false, updatable = false)
    private String providerReference;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Payment(String idempotencyKey, PaymentStatus status, String providerReference, BigDecimal amount) {
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.providerReference = providerReference;
        this.amount = amount;
        this.createdAt = Instant.now();
    }
}
