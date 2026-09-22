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
 * A cinema building, root of theater -> screens -> seats. Not mapped to screens (screens are
 * queried by {@code theaterId} instead). Schema is owned by Liquibase.
 */
@Entity
@Table(name = "theaters")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Theater {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String location;

    /**
     * ISO-4217 code for every price under this theater. Bookings snapshot it, so editing a theater
     * never moves an existing charge.
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
