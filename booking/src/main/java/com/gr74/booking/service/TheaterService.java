package com.gr74.booking.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.controller.dto.ScreenFilter;
import com.gr74.booking.controller.dto.SeatFilter;
import com.gr74.booking.controller.dto.SeatTypeFilter;
import com.gr74.booking.controller.dto.TheaterFilter;
import com.gr74.booking.dto.CreateScreenRequest;
import com.gr74.booking.dto.CreateSeatRequest;
import com.gr74.booking.dto.CreateSeatTypeRequest;
import com.gr74.booking.dto.CreateTheaterRequest;
import com.gr74.booking.dto.GenerateSeatGridRequest;
import com.gr74.booking.exception.BookingErrorCode;
import com.gr74.booking.exception.BookingException;
import com.gr74.booking.exception.DuplicateResourceException;
import com.gr74.booking.exception.ResourceNotFoundException;
import com.gr74.booking.model.Screen;
import com.gr74.booking.model.Seat;
import com.gr74.booking.model.SeatType;
import com.gr74.booking.model.Theater;
import com.gr74.booking.repository.ScreenRepository;
import com.gr74.booking.repository.SeatRepository;
import com.gr74.booking.repository.SeatTypeRepository;
import com.gr74.booking.repository.TheaterRepository;
import com.gr74.booking.repository.spec.ScreenSpecifications;
import com.gr74.booking.repository.spec.SeatSpecifications;
import com.gr74.booking.repository.spec.SeatTypeSpecifications;
import com.gr74.booking.repository.spec.TheaterSpecifications;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Write + read business logic for the inventory tree (theaters → screens → seats) and the seat-type
 * lookup. This is the "inventory service inside Booking" — the immutable-facts layer the Phase-3 saga
 * reads once and snapshots. No saga concerns here; just CRUD with parent-existence and uniqueness
 * guards.
 *
 * <p>Two-layer uniqueness (same lesson as {@code payment}'s idempotency): a pre-check
 * ({@code existsBy…}) gives a friendly {@code BOOKING_DUPLICATE} in the common case, and the DB's
 * {@code UNIQUE} constraint is the real guard for the concurrent-duplicate race — a
 * {@code DataIntegrityViolationException} from a lost race is translated to the same 409 by
 * {@code GlobalExceptionHandler}. Every mutating method is {@code @Transactional}; reads are
 * {@code readOnly} so the deliberate fetch (e.g. seats-with-seat-type) happens inside an open session
 * ({@code open-in-view: false}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterService {

    /**
     * The only entity properties each listing may be sorted by. A whitelist (not a free-for-all) keeps
     * the sort clause safe and the contract explicit — an unknown or injected sort field is rejected as
     * {@code BOOKING_VALIDATION_ERROR} rather than passed to Hibernate (which would surface as an opaque
     * 500). Same lesson as catalog's {@code MovieService.SORTABLE_FIELDS}.
     */
    private static final Set<String> THEATER_SORTABLE = Set.of("name", "location", "createdDate");
    private static final Set<String> SCREEN_SORTABLE = Set.of("name", "screenType", "createdDate");
    private static final Set<String> SEAT_SORTABLE = Set.of("seatRow", "seatNumber", "id");
    private static final Set<String> SEAT_TYPE_SORTABLE = Set.of("name", "priceMultiplier", "createdDate");

    private final TheaterRepository theaterRepository;
    private final ScreenRepository screenRepository;
    private final SeatRepository seatRepository;
    private final SeatTypeRepository seatTypeRepository;

    // ---- Theaters -----------------------------------------------------------------------------

    /**
     * Page through theaters matching the (possibly empty) filter. The sort is validated against
     * {@link #THEATER_SORTABLE} before the query so an unknown sort field is a coded 400, not a leaked
     * persistence error. An empty filter yields an unrestricted-but-paged query.
     */
    @Transactional(readOnly = true)
    public Page<Theater> listTheaters(TheaterFilter filter, Pageable pageable) {
        validateSort(pageable.getSort(), THEATER_SORTABLE);
        log.info("List theaters: filter={}, page={}, size={}, sort={}",
                filter, pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());
        return theaterRepository.findAll(TheaterSpecifications.from(filter), pageable);
    }

    @Transactional
    public Theater createTheater(CreateTheaterRequest request) {
        if (theaterRepository.existsByName(request.name())) {
            throw new DuplicateResourceException("A theater named '" + request.name() + "' already exists");
        }
        Theater saved = theaterRepository.save(new Theater(request.name(), request.location(), request.currency()));
        log.info("Created theater id={} name='{}'", saved.getId(), saved.getName());
        return saved;
    }

    @Transactional
    public void deleteTheater(long theaterId) {
        Theater theater = requireTheater(theaterId);
        theaterRepository.delete(theater);
        log.info("Deleted theater id={}", theaterId);
    }

    // ---- Screens ------------------------------------------------------------------------------

    /**
     * Page through a theater's screens matching the filter. The parent theater must exist (coded 404
     * otherwise); the {@code inTheater} scope is always applied so a caller only ever sees one theater's
     * screens.
     */
    @Transactional(readOnly = true)
    public Page<Screen> listScreens(long theaterId, ScreenFilter filter, Pageable pageable) {
        requireTheater(theaterId);
        validateSort(pageable.getSort(), SCREEN_SORTABLE);
        return screenRepository.findAll(ScreenSpecifications.from(theaterId, filter), pageable);
    }

    @Transactional
    public Screen createScreen(long theaterId, CreateScreenRequest request) {
        Theater theater = requireTheater(theaterId);
        if (screenRepository.existsByTheaterIdAndName(theaterId, request.name())) {
            throw new DuplicateResourceException(
                    "A screen named '" + request.name() + "' already exists in theater id=" + theaterId);
        }
        Screen saved = screenRepository.save(new Screen(request.name(), request.screenType(), theater));
        log.info("Created screen id={} name='{}' in theater id={}", saved.getId(), saved.getName(), theaterId);
        return saved;
    }

    @Transactional
    public void deleteScreen(long screenId) {
        Screen screen = requireScreen(screenId);
        screenRepository.delete(screen);
        log.info("Deleted screen id={}", screenId);
    }

    // ---- Seat types ---------------------------------------------------------------------------

    /** Page through seat types matching the filter (no exemption from paging — per the convention). */
    @Transactional(readOnly = true)
    public Page<SeatType> listSeatTypes(SeatTypeFilter filter, Pageable pageable) {
        validateSort(pageable.getSort(), SEAT_TYPE_SORTABLE);
        return seatTypeRepository.findAll(SeatTypeSpecifications.from(filter), pageable);
    }

    @Transactional
    public SeatType createSeatType(CreateSeatTypeRequest request) {
        if (seatTypeRepository.existsByName(request.name())) {
            throw new DuplicateResourceException("A seat type named '" + request.name() + "' already exists");
        }
        SeatType saved = seatTypeRepository.save(new SeatType(request.name(), request.priceMultiplier()));
        log.info("Created seat type id={} name='{}'", saved.getId(), saved.getName());
        return saved;
    }

    @Transactional
    public void deleteSeatType(long seatTypeId) {
        SeatType seatType = requireSeatType(seatTypeId);
        seatTypeRepository.delete(seatType);
        log.info("Deleted seat type id={}", seatTypeId);
    }

    // ---- Seats --------------------------------------------------------------------------------

    /**
     * Page through a screen's seats matching the filter. The parent screen must exist (coded 404); the
     * {@code onScreen} scope is always applied. Uses {@link SeatRepository#findSeatPage} so each seat's
     * {@code seatType} is fetched for the {@code SeatResponse} mapper under {@code open-in-view: false}.
     */
    @Transactional(readOnly = true)
    public Page<Seat> listSeats(long screenId, SeatFilter filter, Pageable pageable) {
        requireScreen(screenId);
        validateSort(pageable.getSort(), SEAT_SORTABLE);
        return seatRepository.findSeatPage(SeatSpecifications.from(screenId, filter), pageable);
    }

    @Transactional
    public Seat createSeat(long screenId, CreateSeatRequest request) {
        Screen screen = requireScreen(screenId);
        SeatType seatType = requireSeatType(request.seatTypeId());
        if (seatRepository.existsByScreenIdAndSeatRowAndSeatNumber(
                screenId, request.seatRow(), request.seatNumber())) {
            throw new DuplicateResourceException(
                    "Seat " + request.seatRow() + request.seatNumber()
                            + " already exists on screen id=" + screenId);
        }
        Seat saved = seatRepository.save(
                new Seat(request.seatRow(), request.seatNumber(), screen, seatType));
        log.info("Created seat id={} {}{} on screen id={}",
                saved.getId(), saved.getSeatRow(), saved.getSeatNumber(), screenId);
        return saved;
    }

    /**
     * Bulk-create an {@code rows × seatsPerRow} grid on a screen (plan option 5B). Rows are labelled
     * A, B, C, …; positions that already exist are <em>skipped</em>, not rejected, so re-running the
     * generator (or extending a grid) is idempotent. Returns only the seats actually created.
     */
    @Transactional
    public List<Seat> generateSeatGrid(long screenId, GenerateSeatGridRequest request) {
        Screen screen = requireScreen(screenId);
        SeatType seatType = requireSeatType(request.seatTypeId());

        List<Seat> created = new ArrayList<>();
        for (int r = 0; r < request.rows(); r++) {
            String rowLabel = String.valueOf((char) ('A' + r)); // 0->A, 1->B, … (rows capped at 26 in the DTO)
            for (int number = 1; number <= request.seatsPerRow(); number++) {
                if (seatRepository.existsByScreenIdAndSeatRowAndSeatNumber(screenId, rowLabel, number)) {
                    continue; // idempotent: leave an already-placed seat untouched
                }
                created.add(new Seat(rowLabel, number, screen, seatType));
            }
        }
        List<Seat> saved = seatRepository.saveAll(created);
        log.info("Generated {} seats ({}x{}) on screen id={}",
                saved.size(), request.rows(), request.seatsPerRow(), screenId);
        return saved;
    }

    @Transactional
    public void deleteSeat(long seatId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> ResourceNotFoundException.seat(seatId));
        seatRepository.delete(seat);
        log.info("Deleted seat id={}", seatId);
    }

    // ---- Lookups (shared by both this service and ShowtimeService) ----------------------------

    /** Load a theater or raise the coded 404. */
    @Transactional(readOnly = true)
    public Theater requireTheater(long theaterId) {
        return theaterRepository.findById(theaterId)
                .orElseThrow(() -> ResourceNotFoundException.theater(theaterId));
    }

    /** Load a screen or raise the coded 404. Reused by {@code ShowtimeService} to validate the FK. */
    @Transactional(readOnly = true)
    public Screen requireScreen(long screenId) {
        return screenRepository.findById(screenId)
                .orElseThrow(() -> ResourceNotFoundException.screen(screenId));
    }

    private SeatType requireSeatType(long seatTypeId) {
        return seatTypeRepository.findById(seatTypeId)
                .orElseThrow(() -> ResourceNotFoundException.seatType(seatTypeId));
    }

    /** Reject any requested sort property not in the resource's whitelist as a coded validation error. */
    private void validateSort(Sort sort, Set<String> sortable) {
        for (Sort.Order order : sort) {
            if (!sortable.contains(order.getProperty())) {
                throw new BookingException(BookingErrorCode.BOOKING_VALIDATION_ERROR,
                        "Cannot sort by '" + order.getProperty() + "'. Sortable fields: " + sortable);
            }
        }
    }
}
