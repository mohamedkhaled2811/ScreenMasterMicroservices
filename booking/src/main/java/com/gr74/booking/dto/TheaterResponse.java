package com.gr74.booking.dto;

import com.gr74.booking.model.Theater;

/**
 * Response for a theater.
 */
public record TheaterResponse(Long id, String name, String location, String currency) {

    public static TheaterResponse from(Theater theater) {
        return new TheaterResponse(
                theater.getId(), theater.getName(), theater.getLocation(), theater.getCurrency());
    }
}
