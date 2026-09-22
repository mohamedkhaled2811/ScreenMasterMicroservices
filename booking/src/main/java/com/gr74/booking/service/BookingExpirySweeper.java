package com.gr74.booking.service;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scheduled clock that expires lapsed seat holds. Only the clock and error boundary;
 * the work and its transaction live in {@link BookingExpirer}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpirySweeper {

    private final BookingExpirer expirer;
    private final Clock clock;

    /** Fire on the configured cadence; a failed tick is caught so the scheduler thread survives. */
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
