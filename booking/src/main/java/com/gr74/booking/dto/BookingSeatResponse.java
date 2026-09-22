package com.gr74.booking.dto;

import java.math.BigDecimal;

import com.gr74.booking.model.BookingSeat;

/**
 * One reserved-seat line item on a booking, with price and seat-type name snapshotted at booking time.
 */
public record BookingSeatResponse(
        Long seatId,
        BigDecimal seatPrice,
        String seatTypeName) {

    public static BookingSeatResponse from(BookingSeat seat) {
        return new BookingSeatResponse(seat.getSeatId(), seat.getSeatPrice(), seat.getSeatTypeName());
    }
}
