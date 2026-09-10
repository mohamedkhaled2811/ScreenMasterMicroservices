package com.gr74.booking.dto;

import com.gr74.booking.model.Theater;

/**
 * Response shape for a theater — a DTO, not the {@link Theater} entity (project convention: DTOs cross
 * the wire). Auditing fields are internal and deliberately not exposed.
 */
public record TheaterResponse(Long id, String name, String location, String currency) {

    public static TheaterResponse from(Theater theater) {
        return new TheaterResponse(
                theater.getId(), theater.getName(), theater.getLocation(), theater.getCurrency());
    }
}
