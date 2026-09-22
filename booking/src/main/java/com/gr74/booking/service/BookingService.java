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

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bookings: the write side (create) and the read side ("my bookings").
 * Creates a PENDING booking with a 15-minute hold; pricing is derived server-side
 * and frozen onto each line item. A pre-check gives a friendly 409, and the DB
 * unique constraint is the backstop for the concurrent race.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    /** How long a created booking holds its seats before the sweeper may expire it. */
    private static final Duration HOLD_WINDOW = Duration.ofMinutes(15);

    /** Statuses that still reserve a seat — used by the double-booking guard. */
    private static final Set<BookingStatus> ACTIVE_STATUSES = Set.of(BookingStatus.PENDING, BookingStatus.CONFIRMED);

    private final BookingRepository bookingRepository;
    private final ShowtimeRepository showtimeRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;
    private final CatalogClient catalogClient;
    private final MovieReadModel movieReadModel;

    /**
     * The narrow read Payment uses before opening a checkout session, so it never trusts
     * a client for the amount. Returns the facts; payability is decided by Payment.
     */
    @Transactional(readOnly = true)
    public Booking findForPayability(long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> ResourceNotFoundException.booking(bookingId));
    }

    @Transactional
    @Observed(name = "booking.create", contextualName = "create-booking")
    public Booking create(String userId, CreateBookingRequest request) {
        // 1) The showtime must exist — it carries the screen, movieId, basePrice, and currency.
        // Fetch-joined with screen and theater (LAZY under open-in-view: false).
        Showtime showtime = showtimeRepository.findWithScreenAndTheaterById(request.showtimeId())
                .orElseThrow(() -> ResourceNotFoundException.showtime(request.showtimeId()));

        // 2) Load requested seats with their seat type, de-duping the input first.
        List<Long> seatIds = request.seatIds().stream().distinct().toList();
        List<Seat> seats = seatRepository.findWithSeatTypeByIdIn(seatIds);
        if (seats.size() != seatIds.size()) {
            // At least one requested seat id doesn't exist.
            Set<Long> found = seats.stream().map(Seat::getId).collect(Collectors.toSet());
            Long missing = seatIds.stream().filter(id -> !found.contains(id)).findFirst().orElseThrow();
            throw ResourceNotFoundException.seat(missing);
        }

        // 3) Every seat must belong to this showtime's screen.
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

        // 4) Double-booking guard: reject seats already held for this showtime.
        List<Long> alreadyHeld =
                bookingRepository.findSeatIdsHeldForShowtime(showtime.getId(), seatIds, ACTIVE_STATUSES);
        if (!alreadyHeld.isEmpty()) {
            throw new DuplicateResourceException(
                    "Seat(s) " + alreadyHeld + " already held for showtime id=" + showtime.getId());
        }

        // 5) Price each seat server-side and sum the total.
        List<BookingSeat> lineItems = seats.stream()
                .map(seat -> new BookingSeat(
                        seat.getId(),
                        showtime.getBasePrice().multiply(seat.getSeatType().getPriceMultiplier()),
                        seat.getSeatType().getName()))
                .toList();
        BigDecimal total = lineItems.stream()
                .map(BookingSeat::getSeatPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 6) Assemble the PENDING booking with a 15-min hold; snapshot movieId and currency.
        Instant expiresAt = clock.instant().plus(HOLD_WINDOW);
        String currency = showtime.getScreen().getTheater().getCurrency();
        Booking booking = new Booking(newReference(), userId, showtime.getId(), showtime.getMovieId(),
                total, currency, expiresAt);
        lineItems.forEach(booking::addSeat);

        Booking saved = bookingRepository.save(booking);
        log.info("Created booking id={} ref={} userId={} showtimeId={} seats={} total={} {} (PENDING; "
                        + "pay via POST /payments)",
                saved.getId(), saved.getBookingReference(), userId, showtime.getId(), seatIds.size(),
                total, currency);
        return saved;
    }

    /**
     * List the user's bookings with each movie title. Titles resolve in one batch call
     * per page (never one call per row); a miss renders as {@code null}.
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
     * Resolve titles live from Catalog or from the local read model. Composition degrades
     * to null titles when Catalog is down; the read model serves cached titles instead.
     */
    private Map<Long, String> resolveTitles(MovieDataSource source, Set<Long> movieIds) {
        return switch (source) {
            case COMPOSITION -> catalogClient.titlesByIds(movieIds);
            case READMODEL -> movieReadModel.titlesByIds(movieIds);
        };
    }

    /** Human-facing "BK-XXXXXXXX" handle; uniqueness backed by the DB constraint. */
    private static String newReference() {
        return "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
