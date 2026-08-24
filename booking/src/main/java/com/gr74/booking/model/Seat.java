package com.gr74.booking.model;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A single, individually-reservable seat in a {@link Screen}. The {@code (screen, seatRow,
 * seatNumber)} triple is unique ({@code uq_seats_screen_row_number}) — this is the identity a Phase-3
 * booking reserves against, and what makes the double-booking guard possible (you reserve a specific
 * seat, not a count; plan option 5B).
 *
 * <p>Both links are {@code @ManyToOne(LAZY)} intra-Booking FKs. {@code seatRow} is a {@link String}
 * label ("A", "B", …), not an int, so alphabetic row labels work.
 *
 * <p>Audited like the rest of the inventory slice: {@code 001-create-inventory.yaml} declares
 * {@code created_date NOT NULL} on {@code seats}, so the {@code @CreatedDate} field is what makes a
 * bulk grid insert succeed — without it every row is rejected by the not-null constraint. Note that
 * {@code ddl-auto=validate} does <em>not</em> catch this class of drift: it flags columns the entity
 * expects but the table lacks, not NOT NULL columns the entity silently ignores.
 */
@Entity
@Table(
        name = "seats",
        // Mirrors the Liquibase uq_seats_screen_row_number so Hibernate's schema (@DataJpaTest)
        // enforces the same "a seat position is unique within a screen" invariant.
        uniqueConstraints = @UniqueConstraint(
                name = "uq_seats_screen_row_number",
                columnNames = {"screen_id", "seat_row", "seat_number"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seat_row", nullable = false)
    private String seatRow;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "screen_id", nullable = false)
    private Screen screen;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_type_id", nullable = false)
    private SeatType seatType;

    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

    @LastModifiedDate
    @Column(name = "last_modified_date")
    private Instant lastModifiedDate;

    public Seat(String seatRow, Integer seatNumber, Screen screen, SeatType seatType) {
        this.seatRow = seatRow;
        this.seatNumber = seatNumber;
        this.screen = screen;
        this.seatType = seatType;
    }
}
