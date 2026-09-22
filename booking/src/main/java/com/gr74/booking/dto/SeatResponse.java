package com.gr74.booking.dto;

import com.gr74.booking.model.Seat;

/**
 * Response for a seat, carrying the owning screen id and seat-type details.
 */
public record SeatResponse(
        Long id, String seatRow, Integer seatNumber, Long screenId, Long seatTypeId, String seatTypeName) {

    public static SeatResponse from(Seat seat) {
        return new SeatResponse(
                seat.getId(),
                seat.getSeatRow(),
                seat.getSeatNumber(),
                seat.getScreen().getId(),
                seat.getSeatType().getId(),
                seat.getSeatType().getName());
    }
}
