package com.gr74.booking.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.repository.BookingRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The transactional half of hold expiry: flips every lapsed PENDING booking to EXPIRED.
 *
 * <p><b>Seats are freed by the status flip itself.</b> {@code findSeatIdsHeldForShowtime} only
 * counts PENDING/CONFIRMED, so EXPIRED already releases the seats; the {@code booking_seats}
 * rows stay as the audit trail. And the flip is conditional on still-PENDING — the same guard as
 * the confirm — so a payment confirming inside the hold window wins even if the sweep runs in the
 * same instant: whichever statement matches first takes the row out of PENDING and the other
 * updates zero rows. The database decides the race, not code order.
 *
 * <p><b>Why this is its own bean.</b> {@code @Transactional} is applied by a proxy wrapping the
 * bean, so it only takes effect on calls arriving from <em>outside</em>. When this method lived on
 * {@link BookingExpirySweeper} the scheduled {@code tick()} reached it by plain self-invocation,
 * skipping the proxy entirely — the conditional UPDATE then ran with no transaction and threw
 * {@code TransactionRequiredException} on every tick, so no hold was ever expired. Splitting the
 * scheduler from the work makes the proxy boundary structural instead of implicit; the same reason
 * {@link MovieBackfiller} is separate from {@link MovieReadModel}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingExpirer {

    private final BookingRepository bookings;

    /**
     * Expire every PENDING booking past {@code now}. Direct-invocation testable with a frozen
     * clock; returns how many holds were actually flipped (already-EXPIRED rows a concurrent
     * confirm stole contribute zero).
     */
    @Transactional
    public int expireDueBookings(Instant now) {
        List<Booking> lapsed = bookings.findByStatusAndExpiresAtBefore(BookingStatus.PENDING, now);
        int expired = 0;
        for (Booking booking : lapsed) {
            int rows = bookings.expireIfStillPending(
                    booking.getId(), BookingStatus.PENDING, BookingStatus.EXPIRED);
            if (rows == 1) {
                expired++;
                log.info("Expired booking id={} ref={} (hold lapsed) — seats released, rows kept",
                        booking.getId(), booking.getBookingReference());
            }
        }
        return expired;
    }
}
