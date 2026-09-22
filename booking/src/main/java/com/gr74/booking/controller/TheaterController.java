package com.gr74.booking.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.gr74.booking.exception.ApiError;
import com.gr74.booking.service.TheaterService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;

/**
 * REST endpoints for the inventory tree (theaters, screens, seats, seat types).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Inventory", description = "Theaters, screens, seats, and seat types — the admin inventory tree.")
public class TheaterController {

    private final TheaterService theaterService;

    // ---- Theaters -----------------------------------------------------------------------------

    @GetMapping("/theaters")
    @Operation(summary = "List theaters (paged)",
            description = "Paged, filtered list of theaters (PagedModel envelope). Default sort: name ASC.")
    @ApiResponse(responseCode = "200", description = "A page of theaters.")
    @ApiResponse(responseCode = "400", description = "Bad filter or non-whitelisted sort field. code = BOOKING_VALIDATION_ERROR.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public Page<TheaterResponse> listTheaters(
            @Valid @ParameterObject TheaterFilter filter,
            @ParameterObject @PageableDefault(sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<TheaterResponse> page = theaterService.listTheaters(filter, pageable).map(TheaterResponse::from);
        log.info("GET /theaters -> {} of {} match", page.getNumberOfElements(), page.getTotalElements());
        return page;
    }

    @PostMapping("/theaters")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a theater")
    @ApiResponse(responseCode = "201", description = "Theater created.")
    @ApiResponse(responseCode = "400", description = "Invalid body. code = BOOKING_VALIDATION_ERROR.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "A theater with that name already exists. code = BOOKING_DUPLICATE.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public TheaterResponse createTheater(@Valid @RequestBody CreateTheaterRequest request) {
        return TheaterResponse.from(theaterService.createTheater(request));
    }

    @DeleteMapping("/theaters/{theaterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a theater")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "No theater with that id. code = BOOKING_THEATER_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public void deleteTheater(@PathVariable long theaterId) {
        theaterService.deleteTheater(theaterId);
    }

    // ---- Screens ------------------------------------------------------------------------------

    @GetMapping("/theaters/{theaterId}/screens")
    @Operation(summary = "List a theater's screens (paged)",
            description = "Paged, filtered screens under one theater (PagedModel envelope).")
    @ApiResponse(responseCode = "200", description = "A page of screens.")
    @ApiResponse(responseCode = "404", description = "No such theater. code = BOOKING_THEATER_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public Page<ScreenResponse> listScreens(
            @PathVariable long theaterId,
            @Valid @ParameterObject ScreenFilter filter,
            @ParameterObject @PageableDefault(sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<ScreenResponse> page =
                theaterService.listScreens(theaterId, filter, pageable).map(ScreenResponse::from);
        log.info("GET /theaters/{}/screens -> {} of {} match",
                theaterId, page.getNumberOfElements(), page.getTotalElements());
        return page;
    }

    @PostMapping("/theaters/{theaterId}/screens")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a screen under a theater")
    @ApiResponse(responseCode = "201", description = "Screen created.")
    @ApiResponse(responseCode = "400", description = "Invalid body. code = BOOKING_VALIDATION_ERROR.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No such theater. code = BOOKING_THEATER_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Duplicate screen name in this theater. code = BOOKING_DUPLICATE.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public ScreenResponse createScreen(
            @PathVariable long theaterId, @Valid @RequestBody CreateScreenRequest request) {
        return ScreenResponse.from(theaterService.createScreen(theaterId, request));
    }

    @DeleteMapping("/screens/{screenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a screen")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "No such screen. code = BOOKING_SCREEN_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public void deleteScreen(@PathVariable long screenId) {
        theaterService.deleteScreen(screenId);
    }

    // ---- Seats --------------------------------------------------------------------------------

    @GetMapping("/screens/{screenId}/seats")
    @Operation(summary = "List a screen's seats (paged)",
            description = "Paged, filtered seats for one screen (PagedModel envelope). Default sort: seatRow, seatNumber ASC.")
    @ApiResponse(responseCode = "200", description = "A page of seats.")
    @ApiResponse(responseCode = "404", description = "No such screen. code = BOOKING_SCREEN_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public Page<SeatResponse> listSeats(
            @PathVariable long screenId,
            @Valid @ParameterObject SeatFilter filter,
            @ParameterObject @PageableDefault(sort = {"seatRow", "seatNumber"}, direction = Sort.Direction.ASC) Pageable pageable) {
        Page<SeatResponse> page = theaterService.listSeats(screenId, filter, pageable).map(SeatResponse::from);
        log.info("GET /screens/{}/seats -> {} of {} match",
                screenId, page.getNumberOfElements(), page.getTotalElements());
        return page;
    }

    @PostMapping("/screens/{screenId}/seats")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a single seat on a screen")
    @ApiResponse(responseCode = "201", description = "Seat created.")
    @ApiResponse(responseCode = "400", description = "Invalid body. code = BOOKING_VALIDATION_ERROR.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No such screen or seat type. code = BOOKING_SCREEN_NOT_FOUND / BOOKING_SEAT_TYPE_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "That seat (row+number) already exists on the screen. code = BOOKING_DUPLICATE.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public SeatResponse createSeat(
            @PathVariable long screenId, @Valid @RequestBody CreateSeatRequest request) {
        return SeatResponse.from(theaterService.createSeat(screenId, request));
    }

    /** Bulk seat-grid generation; returns only the seats created by this call. */
    @PostMapping("/screens/{screenId}/seats/grid")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Generate a seat grid (bulk, idempotent)",
            description = "Bulk-creates a rows×columns grid of seats. Idempotent: re-running skips seats that already exist and returns only the seats actually created this call — a plain array (NOT the PagedModel envelope).")
    @ApiResponse(responseCode = "201", description = "The seats created by this call (may be empty on a repeat run).")
    @ApiResponse(responseCode = "400", description = "Invalid grid spec. code = BOOKING_VALIDATION_ERROR.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No such screen or seat type. code = BOOKING_SCREEN_NOT_FOUND / BOOKING_SEAT_TYPE_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public List<SeatResponse> generateSeatGrid(
            @PathVariable long screenId, @Valid @RequestBody GenerateSeatGridRequest request) {
        return theaterService.generateSeatGrid(screenId, request).stream()
                .map(SeatResponse::from)
                .toList();
    }

    @DeleteMapping("/seats/{seatId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a seat")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "No such seat. code = BOOKING_SEAT_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public void deleteSeat(@PathVariable long seatId) {
        theaterService.deleteSeat(seatId);
    }

    // ---- Seat types ---------------------------------------------------------------------------

    @GetMapping("/seat-types")
    @Operation(summary = "List seat types (paged)",
            description = "Paged, filtered seat types (PagedModel envelope). Default sort: name ASC.")
    @ApiResponse(responseCode = "200", description = "A page of seat types.")
    @ApiResponse(responseCode = "400", description = "Bad filter or non-whitelisted sort field. code = BOOKING_VALIDATION_ERROR.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public Page<SeatTypeResponse> listSeatTypes(
            @Valid @ParameterObject SeatTypeFilter filter,
            @ParameterObject @PageableDefault(sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<SeatTypeResponse> page = theaterService.listSeatTypes(filter, pageable).map(SeatTypeResponse::from);
        log.info("GET /seat-types -> {} of {} match", page.getNumberOfElements(), page.getTotalElements());
        return page;
    }

    @PostMapping("/seat-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a seat type")
    @ApiResponse(responseCode = "201", description = "Seat type created.")
    @ApiResponse(responseCode = "400", description = "Invalid body. code = BOOKING_VALIDATION_ERROR.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "A seat type with that name already exists. code = BOOKING_DUPLICATE.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public SeatTypeResponse createSeatType(@Valid @RequestBody CreateSeatTypeRequest request) {
        return SeatTypeResponse.from(theaterService.createSeatType(request));
    }

    @DeleteMapping("/seat-types/{seatTypeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a seat type")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "No such seat type. code = BOOKING_SEAT_TYPE_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public void deleteSeatType(@PathVariable long seatTypeId) {
        theaterService.deleteSeatType(seatTypeId);
    }
}
