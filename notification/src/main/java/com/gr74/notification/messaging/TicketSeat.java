package com.gr74.notification.messaging;

import java.math.BigDecimal;

/**
 * One seat as printed on the ticket; the label is already human-facing (e.g. {@code E5}).
 *
 * @param label        human-facing seat, e.g. {@code E5}
 * @param seatTypeName the tier as named at booking time, e.g. {@code STANDARD}
 * @param price        what this seat cost, frozen at booking time
 */
public record TicketSeat(String label, String seatTypeName, BigDecimal price) {
}
