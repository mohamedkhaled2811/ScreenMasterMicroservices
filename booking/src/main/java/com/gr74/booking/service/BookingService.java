package com.gr74.booking.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.client.CatalogClient;
import com.gr74.booking.dto.CreateBookingRequest;
import com.gr74.booking.dto.MyBookingDto;
import com.gr74.booking.exception.BookingErrorCode;
import com.gr74.booking.exception.BookingException;
import com.gr74.booking.exception.DuplicateResourceException;
import com.gr74.booking.exception.ResourceNotFoundException;
import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingSeat;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.model.Seat;
import com.gr74.booking.model.Showtime;
import com.gr74.booking.repository.BookingRepository;
import com.gr74.booking.repository.SeatRepository;
import com.gr74.booking.repository.ShowtimeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bookings: the write side (create) and the read side ("my bookings").
 *
 * <p>Both live here because they are one aggregate in one service — Booking. The read side was briefly a
 * separate {@code MyBookingsService} bean, but it is a single repository call plus a title lookup, and a
 * bean boundary inside the same module bought nothing. The distributed-systems boundary that <em>does</em>
 * matter is the one to Catalog, and that is expressed by {@link CatalogClient} / {@link MovieReadModel},
 * not by splitting this class.
 *
 * <h2>The write side</h2>
 * Creates bookings — the <em>minimal</em> write, deliberately without the saga.
 *
 * <p>This is BUILD_PLAN 2.2's option 4A: a real {@code POST /bookings} so "my bookings" reads rows a
 * user actually created, and a head start on Phase 3.1 ("Booking core: create PENDING, hold seats").
 * What it does: validate the showtime and seats, run the <b>local double-booking guard</b>, snapshot the
 * per-seat price and the showtime's {@code movieId}, and persist a {@code PENDING} booking with a 15-min
 * hold. What it deliberately does <b>not</b> do: call Payment or compensate — that orchestration is the
 * Phase-3 saga, which will wrap this create rather than replace it.
 *
 * <p>The double-booking guard is the one invariant that must stay strongly consistent: a pre-check
 * ({@link BookingRepository#findSeatIdsHeldForShowtime}) gives a friendly 409, and the
 * {@code uq_booking_seats_booking_seat} unique constraint plus real seat-hold logic (Phase 3) are the
 * backstop against the concurrent race the pre-check can't see. Pricing is derived server-side
 * ({@code showtime.basePrice × seatType.priceMultiplier}) and frozen onto each line item — a client
 * never names its own price.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    /** How long a created booking holds its seats before the Phase-3 sweeper may expire it. */
    private static final Duration HOLD_WINDOW = Duration.ofMinutes(15);

    /** Statuses that still reserve a seat — used by the double-booking guard. */
    private static final Set<BookingStatus> ACTIVE_STATUSES = Set.of(BookingStatus.PENDING, BookingStatus.CONFIRMED);

    private final BookingRepository bookingRepository;
    private final ShowtimeRepository showtimeRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;
    private final CatalogClient catalogClient;
    private final MovieReadModel movieReadModel;

    @Transactional
    public Booking create(String userId, CreateBookingRequest request) {
        // 1) The showtime must exist — it carries the screen (seats must belong to it) and the movieId
        //    we snapshot onto the booking, plus the basePrice the per-seat price is derived from.
        Showtime showtime = showtimeRepository.findById(request.showtimeId())
                .orElseThrow(() -> ResourceNotFoundException.showtime(request.showtimeId()));

        // 2) Load the requested seats WITH their seat type (for pricing), de-duping the input first.
        List<Long> seatIds = request.seatIds().stream().distinct().toList();
        List<Seat> seats = seatRepository.findWithSeatTypeByIdIn(seatIds);
        if (seats.size() != seatIds.size()) {
            // At least one requested seat id doesn't exist.
            Set<Long> found = seats.stream().map(Seat::getId).collect(Collectors.toSet());
            Long missing = seatIds.stream().filter(id -> !found.contains(id)).findFirst().orElseThrow();
            throw ResourceNotFoundException.seat(missing);
        }

        // 3) Every seat must belong to THIS showtime's screen — you can't reserve a seat from another room.
        long screenId = showtime.getScreen().getId();
        Seat wrongScreen = seats.stream()
                .filter(s -> !s.getScreen().getId().equals(screenId))
                .findFirst()
                .orElse(null);
        if (wrongScreen != null) {
            throw new BookingException(BookingErrorCode.BOOKING_VALIDATION_ERROR,
                    "Seat id=" + wrongScreen.getId() + " is not on screen id=" + screenId
                            + " (the showtime's screen).");
        }

        // 4) Local double-booking guard: reject if any requested seat is already held for this showtime.
        List<Long> alreadyHeld =
                bookingRepository.findSeatIdsHeldForShowtime(showtime.getId(), seatIds, ACTIVE_STATUSES);
        if (!alreadyHeld.isEmpty()) {
            throw new DuplicateResourceException(
                    "Seat(s) " + alreadyHeld + " already held for showtime id=" + showtime.getId());
        }

        // 5) Price each seat (basePrice × its seat-type multiplier), snapshotting the frozen line items,
        //    and sum the total — all derived server-side, never from the request.
        List<BookingSeat> lineItems = seats.stream()
                .map(seat -> new BookingSeat(
                        seat.getId(),
                        showtime.getBasePrice().multiply(seat.getSeatType().getPriceMultiplier()),
                        seat.getSeatType().getName()))
                .toList();
        BigDecimal total = lineItems.stream()
                .map(BookingSeat::getSeatPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 6) Assemble the PENDING booking with a 15-min hold; snapshot movieId from the showtime.
        Instant expiresAt = clock.instant().plus(HOLD_WINDOW);
        Booking booking = new Booking(newReference(), userId, showtime.getId(), showtime.getMovieId(),
                total, expiresAt);
        lineItems.forEach(booking::addSeat);

        Booking saved = bookingRepository.save(booking);
        log.info("Created booking id={} ref={} userId={} showtimeId={} seats={} total={} (PENDING, no payment yet)",
                saved.getId(), saved.getBookingReference(), userId, showtime.getId(), seatIds.size(), total);
        return saved;
    }

    // ===== Read side: "my bookings" (the M2 cross-service query) =====

    /**
     * The M2 lesson made concrete: "show me my bookings, with the movie title for each."
     *
     * <p>The monolith did this with a single {@code JOIN booking → showtime → movie}. After decomposition
     * the title lives in another service's database, so this method <b>hand-writes that JOIN over the
     * network</b>:
     * <ol>
     *   <li>read the user's own bookings (paged) from booking-db;</li>
     *   <li>collect the page's <em>distinct</em> {@code movieId}s (snapshotted onto each booking at create);</li>
     *   <li>resolve them to titles in <b>one</b> call — not one call per row (the network N+1 that naive
     *       composition falls into);</li>
     *   <li>merge the title into each row, {@code null} on a miss.</li>
     * </ol>
     *
     * <p><b>Two resolution strategies, chosen per request</b> ({@code ?source=}) so both are demoable
     * side-by-side (BUILD_PLAN 2.4). The bookings query and the {@link MyBookingDto} shape are identical;
     * only where the title comes from differs — see {@link MovieDataSource} and {@link #resolveTitles}.
     */
    @Transactional(readOnly = true)
    public Page<MyBookingDto> myBookings(String userId, Pageable pageable, MovieDataSource source) {
        Page<Booking> bookings = bookingRepository.findByUserId(userId, pageable);
        Set<Long> movieIds = bookings.stream().map(Booking::getMovieId).collect(Collectors.toSet());

        Map<Long, String> titles = resolveTitles(source, movieIds);
        log.info("My bookings for userId={} via {}: {} row(s), resolved {}/{} title(s)",
                userId, source, bookings.getNumberOfElements(), titles.size(), movieIds.size());

        return bookings.map(b -> MyBookingDto.of(b, titles.get(b.getMovieId())));
    }

    /**
     * Way A resolves live from Catalog (one batch call); way B resolves from the local read model.
     *
     * <p>Way A <b>degrades</b>: if Catalog is down it returns no titles, so the list still answers with
     * {@code movieTitle: null} instead of a 503 — the partial-failure trade this path exists to make you
     * feel. Way B reads Booking's own {@code movie_projections} table, lazy-backfilling a cache miss, so a
     * Catalog outage doesn't stop already-cached titles from rendering — at the price of eventual
     * consistency (a rename lags until the event lands).
     */
    private Map<Long, String> resolveTitles(MovieDataSource source, Set<Long> movieIds) {
        return switch (source) {
            case COMPOSITION -> catalogClient.titlesByIds(movieIds);
            case READMODEL -> movieReadModel.titlesByIds(movieIds);
        };
    }

    /** A human-facing "BK-XXXXXXXX" handle. Uniqueness is backed by the DB constraint on the column. */
    private static String newReference() {
        return "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
