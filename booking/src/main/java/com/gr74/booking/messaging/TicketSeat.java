package com.gr74.booking.messaging;

import java.math.BigDecimal;

/**
 * One seat as it appears on a ticket — a line item on {@link BookingConfirmed}.
 *
 * <p><b>Why the label and not the seat id.</b> {@code booking_seats} stores {@code seatId}, which is
 * meaningless to a customer; the ticket needs "E5". The label is composed at confirm time from the
 * {@code Seat} row ({@code seatRow} + {@code seatNumber}) and travels on the event, so Notification
 * never needs to resolve a seat id — it has no access to Booking's database and must not acquire one.
 *
 * <p><b>Why the price is repeated per seat</b> when the booking already carries a total: a ticket for a
 * party of four with one accessible seat and three standard ones has to show why the total is what it
 * is. The values are the ones already snapshotted onto {@code booking_seats} at booking time
 * ({@code showtime.basePrice × seatType.priceMultiplier}, frozen), so this is a copy of a snapshot,
 * not a fresh calculation — a re-render years later prints the same numbers.
 *
 * @param label        human-facing seat, e.g. {@code E5} (row + number)
 * @param seatTypeName the tier as it was named at booking time, e.g. {@code STANDARD}, {@code VIP}
 * @param price        what this seat cost, frozen at booking time
 */
public record TicketSeat(String label, String seatTypeName, BigDecimal price) {
}
