package com.gr74.booking.dto;

import com.gr74.booking.model.Seat;

/**
 * Response shape for a seat. Exposes the owning {@code screenId} and the {@code seatTypeId} +
 * {@code seatTypeName} (the latter denormalized for readability) rather than nested entities — a DTO
 * shouldn't drag the {@link Seat}'s lazy associations across the wire.
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
