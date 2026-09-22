package com.gr74.booking.dto;

import java.math.BigDecimal;

import com.gr74.booking.model.SeatType;

/**
 * Response for a seat type.
 */
public record SeatTypeResponse(Long id, String name, BigDecimal priceMultiplier) {

    public static SeatTypeResponse from(SeatType seatType) {
        return new SeatTypeResponse(seatType.getId(), seatType.getName(), seatType.getPriceMultiplier());
    }
}
