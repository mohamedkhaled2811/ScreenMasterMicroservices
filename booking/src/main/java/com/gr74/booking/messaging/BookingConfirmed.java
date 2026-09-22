package com.gr74.booking.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Raised when a hold becomes a sale. Carries a snapshot of the ticket facts
 * so Notification can render without calling back.
 */
public record BookingConfirmed(
        long eventId,
        long bookingId,
        String bookingReference,
        String userId,
        Long movieId,
        String movieTitle,
        String posterPath,
        Instant showtimeStartsAt,
        String theaterName,
        String screenName,
        List<TicketSeat> seats,
        BigDecimal totalAmount,
        String currency,
        Instant occurredAt) {
}
