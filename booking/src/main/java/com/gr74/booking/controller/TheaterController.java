package com.gr74.booking.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gr74.booking.controller.dto.ScreenFilter;
import com.gr74.booking.controller.dto.SeatFilter;
import com.gr74.booking.controller.dto.SeatTypeFilter;
import com.gr74.booking.controller.dto.TheaterFilter;
import com.gr74.booking.dto.CreateScreenRequest;
import com.gr74.booking.dto.CreateSeatRequest;
import com.gr74.booking.dto.CreateSeatTypeRequest;
import com.gr74.booking.dto.CreateTheaterRequest;
import com.gr74.booking.dto.GenerateSeatGridRequest;
import com.gr74.booking.dto.ScreenResponse;
import com.gr74.booking.dto.SeatResponse;
import com.gr74.booking.dto.SeatTypeResponse;
import com.gr74.booking.dto.TheaterResponse;
import com.gr74.booking.service.TheaterService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST surface for the inventory tree (theaters → screens → seats) and seat-types — the write side
 * Booking absorbs from the monolith's Theater context.
 *
 * <p>Paths are <em>bare</em> ({@code /theaters}, {@code /screens/{id}/seats}, {@code /seat-types}); the
 * {@code /api} namespace lives only at the gateway, which strips it via {@code StripPrefix=1} before
 * forwarding ({@code /api/theaters} → {@code /theaters}). This mirrors how {@code payment} exposes
 * {@code /payments} and {@code catalog} exposes {@code /movies} — no edge concern leaks into the service.
 *
 * <p>Every error (missing parent, duplicate, bad body) is thrown from the service and translated to an
 * RFC 9457 {@code ProblemDetail} with a stable {@code code} by {@code GlobalExceptionHandler}; these
 * methods describe only the happy path and never {@code return} an error. DTOs cross the wire, not
 * entities (project convention).
 *
 * <p>Every listing endpoint is <b>paged</b> (never an unbounded array) — per the repo convention in
 * {@code docs/concepts/pagination-and-filtering.md}. Each returns a {@code Page<…>} (serialized as the
 * stable {@code PagedModel} envelope via {@code WebPagingConfig}), takes an optional filter DTO bound
 * from query params (composed into a JPA {@code Specification}), and rides Spring Data's {@code Pageable}
 * for {@code page}/{@code size}/{@code sort}. Size is capped at {@code WebPagingConfig.MAX_PAGE_SIZE};
 * an unknown {@code sort} field is a coded {@code BOOKING_VALIDATION_ERROR}. Pages are 0-indexed.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TheaterController {

    private final TheaterService theaterService;

    // ---- Theaters -----------------------------------------------------------------------------

    @GetMapping("/theaters")
    public Page<TheaterResponse> listTheaters(
            @Valid TheaterFilter filter,
            @PageableDefault(sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<TheaterResponse> page = theaterService.listTheaters(filter, pageable).map(TheaterResponse::from);
        log.info("GET /theaters -> {} of {} match", page.getNumberOfElements(), page.getTotalElements());
        return page;
    }

    @PostMapping("/theaters")
    @ResponseStatus(HttpStatus.CREATED)
    public TheaterResponse createTheater(@Valid @RequestBody CreateTheaterRequest request) {
        return TheaterResponse.from(theaterService.createTheater(request));
    }

    @DeleteMapping("/theaters/{theaterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTheater(@PathVariable long theaterId) {
        theaterService.deleteTheater(theaterId);
    }

    // ---- Screens ------------------------------------------------------------------------------

    @GetMapping("/theaters/{theaterId}/screens")
    public Page<ScreenResponse> listScreens(
            @PathVariable long theaterId,
            @Valid ScreenFilter filter,
            @PageableDefault(sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<ScreenResponse> page =
                theaterService.listScreens(theaterId, filter, pageable).map(ScreenResponse::from);
        log.info("GET /theaters/{}/screens -> {} of {} match",
                theaterId, page.getNumberOfElements(), page.getTotalElements());
        return page;
    }

    @PostMapping("/theaters/{theaterId}/screens")
    @ResponseStatus(HttpStatus.CREATED)
    public ScreenResponse createScreen(
            @PathVariable long theaterId, @Valid @RequestBody CreateScreenRequest request) {
        return ScreenResponse.from(theaterService.createScreen(theaterId, request));
    }

    @DeleteMapping("/screens/{screenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteScreen(@PathVariable long screenId) {
        theaterService.deleteScreen(screenId);
    }

    // ---- Seats --------------------------------------------------------------------------------

    @GetMapping("/screens/{screenId}/seats")
    public Page<SeatResponse> listSeats(
            @PathVariable long screenId,
            @Valid SeatFilter filter,
            @PageableDefault(sort = {"seatRow", "seatNumber"}, direction = Sort.Direction.ASC) Pageable pageable) {
        Page<SeatResponse> page = theaterService.listSeats(screenId, filter, pageable).map(SeatResponse::from);
        log.info("GET /screens/{}/seats -> {} of {} match",
                screenId, page.getNumberOfElements(), page.getTotalElements());
        return page;
    }

    @PostMapping("/screens/{screenId}/seats")
    @ResponseStatus(HttpStatus.CREATED)
    public SeatResponse createSeat(
            @PathVariable long screenId, @Valid @RequestBody CreateSeatRequest request) {
        return SeatResponse.from(theaterService.createSeat(screenId, request));
    }

    /** Bulk grid generator (plan option 5B) — returns the seats actually created (idempotent). */
    @PostMapping("/screens/{screenId}/seats/grid")
    @ResponseStatus(HttpStatus.CREATED)
    public List<SeatResponse> generateSeatGrid(
            @PathVariable long screenId, @Valid @RequestBody GenerateSeatGridRequest request) {
        return theaterService.generateSeatGrid(screenId, request).stream()
                .map(SeatResponse::from)
                .toList();
    }

    @DeleteMapping("/seats/{seatId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSeat(@PathVariable long seatId) {
        theaterService.deleteSeat(seatId);
    }

    // ---- Seat types ---------------------------------------------------------------------------

    @GetMapping("/seat-types")
    public Page<SeatTypeResponse> listSeatTypes(
            @Valid SeatTypeFilter filter,
            @PageableDefault(sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<SeatTypeResponse> page = theaterService.listSeatTypes(filter, pageable).map(SeatTypeResponse::from);
        log.info("GET /seat-types -> {} of {} match", page.getNumberOfElements(), page.getTotalElements());
        return page;
    }

    @PostMapping("/seat-types")
    @ResponseStatus(HttpStatus.CREATED)
    public SeatTypeResponse createSeatType(@Valid @RequestBody CreateSeatTypeRequest request) {
        return SeatTypeResponse.from(theaterService.createSeatType(request));
    }

    @DeleteMapping("/seat-types/{seatTypeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSeatType(@PathVariable long seatTypeId) {
        theaterService.deleteSeatType(seatTypeId);
    }
}
