package com.gr74.booking.dto;

import java.math.BigDecimal;

import com.gr74.booking.model.SeatType;

/**
 * Response shape for a seat type — a DTO, not the {@link SeatType} entity (project convention).
 */
public record SeatTypeResponse(Long id, String name, BigDecimal priceMultiplier) {

    public static SeatTypeResponse from(SeatType seatType) {
        return new SeatTypeResponse(seatType.getId(), seatType.getName(), seatType.getPriceMultiplier());
    }
}
