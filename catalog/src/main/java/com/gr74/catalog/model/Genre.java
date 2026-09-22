package com.gr74.catalog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A movie genre. The {@code id} is the TMDB genre id (assigned, not generated).
 */
@Entity
@Table(name = "genres")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs a no-arg ctor; nobody else should use it
public class Genre {

    @Id
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    public Genre(Long id, String name) {
        this.id = id;
        this.name = name;
    }
}
