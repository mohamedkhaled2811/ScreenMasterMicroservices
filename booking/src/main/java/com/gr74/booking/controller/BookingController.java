package com.gr74.booking.controller;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gr74.booking.dto.BookingResponse;
import com.gr74.booking.dto.CreateBookingRequest;
import com.gr74.booking.dto.MyBookingDto;
import com.gr74.booking.exception.ApiError;
import com.gr74.booking.exception.BookingErrorCode;
import com.gr74.booking.exception.BookingException;
import com.gr74.booking.security.CurrentUser;
import com.gr74.booking.service.BookingService;
import com.gr74.booking.service.MyBookingsService;

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
 * REST surface for bookings.
 *
 * <p>Two endpoints, both keyed on the authenticated caller ({@code @CurrentUser} — the {@code X-User-Id}
 * header today, the JWT {@code sub} in Phase 7; identity never rides in the URL or the body):
 * <ul>
 *   <li>{@code POST /bookings} — create a {@code PENDING} booking holding its seats (no Payment yet; the
 *       saga is Phase 3). Price and movie are derived and snapshotted server-side.</li>
 *   <li>{@code GET /bookings/my} — the M2 composition: a paged list of the caller's bookings, each merged
 *       with its movie title resolved live from Catalog. If Catalog is down the list still answers with
 *       {@code movieTitle: null} (degrade, not fail).</li>
 * </ul>
 * Bare paths ({@code /bookings/...}); the gateway strips the {@code /api} prefix. DTOs cross the wire.
 */
@Slf4j
@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
@Tag(name = "Bookings", description = "Create bookings and list your own (with movie titles composed from Catalog).")
public class BookingController {

    /** The only fields {@code GET /bookings/my} may be sorted by — a whitelist keeps the sort safe. */
    private static final Set<String> MY_BOOKINGS_SORTABLE =
            Set.of("createdDate", "expiresAt", "totalAmount", "status");

    private final BookingService bookingService;
    private final MyBookingsService myBookingsService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a booking",
            description = """
                    Reserves the given seats for a showtime as a PENDING booking with a 15-minute hold.
                    The user is taken from the X-User-Id header (the JWT sub in Phase 7), never the body.
                    Price is derived server-side and snapshotted. No payment is taken here — that is the
                    Phase-3 saga.""")
    @ApiResponse(responseCode = "201", description = "Booking created (PENDING).")
    @ApiResponse(responseCode = "400", description = "Invalid body, a seat not on the showtime's screen, or a missing X-User-Id header. code = BOOKING_VALIDATION_ERROR.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "The showtime or a seat does not exist. code = BOOKING_SHOWTIME_NOT_FOUND / BOOKING_SEAT_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "A requested seat is already held for the showtime. code = BOOKING_DUPLICATE.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public BookingResponse create(@CurrentUser String userId, @Valid @RequestBody CreateBookingRequest request) {
        return BookingResponse.from(bookingService.create(userId, request));
    }

    @GetMapping("/my")
    @Operation(summary = "List my bookings (paged, with movie titles)",
            description = """
                    Paged list of the authenticated user's bookings (PagedModel envelope), each merged with
                    its movie title resolved live from the Catalog service (API composition). Default sort:
                    createdDate DESC. If Catalog is unreachable the list still returns with movieTitle=null
                    rather than failing.""")
    @ApiResponse(responseCode = "200", description = "A page of the caller's bookings (PagedModel envelope).")
    @ApiResponse(responseCode = "400", description = "A missing X-User-Id header or a non-whitelisted sort field. code = BOOKING_VALIDATION_ERROR.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public Page<MyBookingDto> myBookings(
            @CurrentUser String userId,
            @ParameterObject @PageableDefault(sort = "createdDate", direction = Sort.Direction.DESC) Pageable pageable) {
        validateSort(pageable.getSort());
        Page<MyBookingDto> page = myBookingsService.myBookings(userId, pageable);
        log.info("GET /bookings/my -> {} of {} booking(s) for userId={}",
                page.getNumberOfElements(), page.getTotalElements(), userId);
        return page;
    }

    /** Reject a sort on a field outside the whitelist as a coded 400 (never a leaked 500). */
    private void validateSort(Sort sort) {
        for (Sort.Order order : sort) {
            if (!MY_BOOKINGS_SORTABLE.contains(order.getProperty())) {
                throw new BookingException(BookingErrorCode.BOOKING_VALIDATION_ERROR,
                        "Cannot sort by '" + order.getProperty() + "'. Sortable fields: " + MY_BOOKINGS_SORTABLE);
            }
        }
    }
}
