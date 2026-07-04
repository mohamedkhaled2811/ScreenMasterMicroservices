package com.gr74.booking.model;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A category of seat (STANDARD, PREMIUM, VIP) with a {@code priceMultiplier}. This is the pricing
 * knob the booking saga reads: a booking's per-seat price is {@code showtime.basePrice ×
 * priceMultiplier}, and Phase 3 <em>snapshots</em> that computed price onto the booking so a later
 * multiplier change never rewrites an existing booking (schema doc §8.2 — snapshot immutable facts).
 *
 * <p>Kept from the monolith on purpose (plan §8 open-question: yes). Schema owned by Liquibase
 * ({@code ddl-auto=validate}); must match {@code 001-create-inventory.yaml}.
 */
@Entity
@Table(name = "seat_types")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "price_multiplier", nullable = false)
    private BigDecimal priceMultiplier;

    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

    @LastModifiedDate
    @Column(name = "last_modified_date")
    private Instant lastModifiedDate;

    public SeatType(String name, BigDecimal priceMultiplier) {
        this.name = name;
        this.priceMultiplier = priceMultiplier;
    }
}
