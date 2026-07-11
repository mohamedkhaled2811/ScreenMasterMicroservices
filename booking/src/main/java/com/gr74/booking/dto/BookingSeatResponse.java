package com.gr74.booking.dto;

import java.math.BigDecimal;

import com.gr74.booking.model.BookingSeat;

/**
 * One reserved-seat line item on a booking. Exposes the snapshotted facts (the price and seat-type name
 * frozen at booking time), not the live seat catalogue — a booking reads standalone even if the seat's
 * type multiplier later changes. DTOs cross the wire, not the {@link BookingSeat} entity.
 */
public record BookingSeatResponse(
        Long seatId,
        BigDecimal seatPrice,
        String seatTypeName) {

    public static BookingSeatResponse from(BookingSeat seat) {
        return new BookingSeatResponse(seat.getSeatId(), seat.getSeatPrice(), seat.getSeatTypeName());
    }
}
