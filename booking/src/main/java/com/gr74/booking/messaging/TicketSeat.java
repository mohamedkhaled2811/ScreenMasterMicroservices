package com.gr74.booking.messaging;

import java.math.BigDecimal;

/**
 * One seat line item on {@link BookingConfirmed}, with label, type name, and price frozen at booking time.
 */
public record TicketSeat(String label, String seatTypeName, BigDecimal price) {
}
