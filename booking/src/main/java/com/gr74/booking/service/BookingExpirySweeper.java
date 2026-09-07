package com.gr74.booking.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.repository.BookingRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Expires lapsed seat holds — one of the two Phase-3 clocks (BUILD_PLAN 3.4).
 *
 * <p>This sweeper answers "whose 15-minute hold ran out?", a different question from Payment's
 * attempt sweeper ("whose gateway session lapsed?") — hence two independent jobs, not one. A
 * lapsed hold frees its seats and the user books again; an in-flight payment that lands late is
 * <em>not</em> this job's problem — it is handled by the confirmer's rejected path plus 3.5's
 * auto-refund, which is why this job emits nothing.
 *
 * <p><b>Seats are freed by the status flip itself.</b> {@code findSeatIdsHeldForShowtime} only
 * counts PENDING/CONFIRMED, so EXPIRED already releases the seats; the {@code booking_seats}
 * rows stay as the audit trail. And the flip is conditional on still-PENDING — the same guard as
 * the confirm — so a payment confirming inside the hold window wins even if this tick runs in the
 * same instant: whichever statement matches first takes the row out of PENDING and the other
 * updates zero rows. The database decides the race, not code order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpirySweeper {

    private final BookingRepository bookings;
    private final Clock clock;

    /**
     * Fire on the configured cadence. Any error escaping a tick is caught here so a single bad
     * run never kills the scheduler thread (the {@code TmdbScheduledTasks} idiom) — a missed pass
     * simply waits for the next tick, because expiry is a freshness knob, not a correctness one.
     */
    @Scheduled(fixedDelayString = "${booking.expiry.sweep-interval-millis:60000}")
    public void tick() {
        try {
            int expired = expireDueBookings(clock.instant());
            if (expired > 0) {
                log.info("Booking expiry sweep expired {} lapsed hold(s)", expired);
            } else {
                log.debug("Booking expiry sweep: nothing lapsed");
            }
        } catch (RuntimeException e) {
            log.error("Booking expiry sweep tick failed (next tick retries): {}", e.getMessage(), e);
        }
    }

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
