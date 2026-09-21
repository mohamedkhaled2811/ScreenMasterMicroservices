package com.gr74.notification.messaging;

import java.math.BigDecimal;

/**
 * One seat as it appears on the ticket — Notification's OWN copy of Booking's {@code TicketSeat} wire
 * shape (cross-service types are duplicated by design: no shared jar, so neither service's deploy can
 * break the other's compile).
 *
 * <p>Already a label ({@code E5}), not a seat id: Notification has no access to Booking's seat
 * catalogue and must never acquire one, so the human-facing string is composed by Booking at confirm
 * time and travels on the event.
 *
 * @param label        human-facing seat, e.g. {@code E5}
 * @param seatTypeName the tier as named at booking time, e.g. {@code STANDARD}
 * @param price        what this seat cost, frozen at booking time
 */
public record TicketSeat(String label, String seatTypeName, BigDecimal price) {
}
