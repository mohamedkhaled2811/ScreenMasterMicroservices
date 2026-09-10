package com.gr74.booking.model;

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
 * A cinema building — the root of the inventory tree (theater → screens → seats). Its {@code id} is
 * <em>generated</em> ({@code IDENTITY}), not assigned: unlike Catalog's TMDB-sourced ids, these rows
 * are minted by us.
 *
 * <p>The association to screens is deliberately <b>not</b> mapped here (no {@code @OneToMany}): with
 * {@code open-in-view: false} a lazy collection would be unreadable after the transaction, and we
 * don't need to navigate theater→screens in memory — the {@code ScreenRepository} queries screens by
 * {@code theaterId} instead. The FK lives on {@link Screen}. Schema is owned by Liquibase
 * ({@code ddl-auto=validate}); this entity must match {@code 001-create-inventory.yaml} or startup fails.
 */
@Entity
@Table(name = "theaters")
@EntityListeners(AuditingEntityListener.class) // populates the @CreatedDate / @LastModifiedDate fields
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs a no-arg ctor; nobody else should use it
public class Theater {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String location;

    /**
     * ISO-4217 code every price under this theater is denominated in.
     *
     * <p>Currency lives here rather than on the showtime or seat type because a theater sits in one
     * country and bills in one currency: one column, no per-row duplication, and no way for two seats
     * in the same room to disagree. A booking <em>snapshots</em> it (like the price and movie id), so
     * editing a theater never moves an existing charge.
     *
     * <p>It matters because Payment picks a gateway from it — Paymob settles EGP, Stripe test mode
     * settles USD — so a booking's currency decides which gateways can take it (changeset 009).
     */
    @Column(nullable = false, length = 3)
    private String currency;

    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

    @LastModifiedDate
    @Column(name = "last_modified_date")
    private Instant lastModifiedDate;

    public Theater(String name, String location, String currency) {
        this.name = name;
        this.location = location;
        this.currency = currency;
    }
}
