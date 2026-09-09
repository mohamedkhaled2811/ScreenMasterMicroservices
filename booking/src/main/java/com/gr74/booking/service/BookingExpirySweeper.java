package com.gr74.booking.service;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 * <p>This class is only the clock and the error boundary; the work — and the transaction it needs
 * — lives in {@link BookingExpirer}. Keeping the catch <em>outside</em> the transaction is
 * deliberate: swallowing an exception inside one would leave a rollback-only transaction that
 * fails again at commit, so the guard below would not actually protect the scheduler thread.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpirySweeper {

    private final BookingExpirer expirer;
    private final Clock clock;

    /**
     * Fire on the configured cadence. Any error escaping a tick is caught here so a single bad
     * run never kills the scheduler thread (the {@code TmdbScheduledTasks} idiom) — a missed pass
     * simply waits for the next tick, because expiry is a freshness knob, not a correctness one.
     */
    @Scheduled(fixedDelayString = "${booking.expiry.sweep-interval-millis:60000}")
    public void tick() {
        try {
            int expired = expirer.expireDueBookings(clock.instant());
            if (expired > 0) {
                log.info("Booking expiry sweep expired {} lapsed hold(s)", expired);
            } else {
                log.debug("Booking expiry sweep: nothing lapsed");
            }
        } catch (RuntimeException e) {
            log.error("Booking expiry sweep tick failed (next tick retries): {}", e.getMessage(), e);
        }
    }
}
