package com.gr74.booking.controller;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gr74.booking.dto.BookingPayabilityResponse;
import com.gr74.booking.dto.BookingResponse;
import com.gr74.booking.dto.CreateBookingRequest;
import com.gr74.booking.dto.MyBookingDto;
import com.gr74.booking.exception.ApiError;
import com.gr74.booking.exception.BookingErrorCode;
import com.gr74.booking.exception.BookingException;
import com.gr74.booking.security.CurrentUser;
import com.gr74.booking.service.BookingService;
import com.gr74.booking.service.MovieDataSource;

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
 * <p>Two endpoints, both keyed on the authenticated caller ({@code @CurrentUser} — the JWT
 * {@code sub}; identity never rides in the URL or the body):
 * <ul>
 *   <li>{@code POST /bookings} — create a {@code PENDING} booking holding its seats (no Payment yet).
 *       Price and movie are derived and snapshotted server-side.</li>
 *   <li>{@code GET /bookings/my} — a paged list of the caller's bookings, each merged with its movie title.
 *       {@code ?source=} selects how the title is resolved: {@code composition} (default — live from
 *       Catalog, degrades to {@code null} if Catalog is down) or {@code readmodel} (Booking's local
 *       title cache, survives a Catalog outage for cached movies).</li>
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a booking",
            description = """
                    Reserves the given seats for a showtime as a PENDING booking with a 15-minute hold.
                    The user is taken from the authenticated JWT subject, never the body.
                    Price is derived server-side and snapshotted. No payment is taken here.""")
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
                    its movie title. The `source` query param picks how the title is resolved — the two
                    answers to the cross-service-query problem, same response either way:
                      • composition (default) — resolved LIVE from Catalog (API composition). Fresh, but if
                        Catalog is down the title degrades to null.
                      • readmodel — resolved from Booking's LOCAL movie_projections cache (kept fresh by events,
                        lazy-backfilled on a miss). Survives Catalog being down for already-cached movies,
                        at the cost of eventual consistency (a rename lags until the event lands).
                    Default sort: createdDate DESC.""")
    @ApiResponse(responseCode = "200", description = "A page of the caller's bookings (PagedModel envelope).")
    @ApiResponse(responseCode = "400", description = "A missing X-User-Id header, a non-whitelisted sort field, or an unknown source value. code = BOOKING_VALIDATION_ERROR.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public Page<MyBookingDto> myBookings(
            @CurrentUser String userId,
            @RequestParam(name = "source", defaultValue = "composition") String source,
            @ParameterObject @PageableDefault(sort = "createdDate", direction = Sort.Direction.DESC) Pageable pageable) {
        validateSort(pageable.getSort());
        MovieDataSource titleSource = parseSource(source);
        Page<MyBookingDto> page = bookingService.myBookings(userId, pageable, titleSource);
        log.info("GET /bookings/my?source={} -> {} of {} booking(s) for userId={}",
                titleSource, page.getNumberOfElements(), page.getTotalElements(), userId);
        return page;
    }

    /**
     * The facts the Payment service needs before opening a checkout session.
     *
     * <p><b>Internal, service-to-service — authenticated with the USER's token, not a machine
     * token</b>. {@code POST /payments} arrives at Payment with the user's
     * Bearer token and Payment relays that same token here, so Booking sees the <em>user</em> — and
     * Payment's "caller owns this booking" comparison runs against a verified identity instead of
     * Payment's word about who's calling. The endpoint reports facts (status, owner, hold deadline,
     * amount, currency) and deliberately makes no payability <em>judgement</em> — those guards live
     * in Payment, and splitting them across both services is how the two sets of rules would drift.
     *
     * <p>Note there is no {@code @CurrentUser} here: the caller is a service relaying a user, not a
     * person hitting this endpoint directly, and it is Payment that compares {@code userId} against
     * its own request's user.
     */
    @GetMapping("/{bookingId}/payability")
    @Operation(summary = "Booking facts for the payment service (internal)",
            description = """
                    Returns the minimal slice the Payment service needs to decide whether a checkout \
                    session may be opened: status, owner, seat-hold deadline, and the authoritative \
                    amount + currency.

                    The amount comes from here — never from the client — so a browser cannot name its own \
                    price. This endpoint makes no judgement about payability; it reports facts and lets \
                    Payment apply its guards.""")
    @ApiResponse(responseCode = "200", description = "The booking's payability facts.",
            content = @Content(schema = @Schema(implementation = BookingPayabilityResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such booking. code = BOOKING_NOT_FOUND.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    public BookingPayabilityResponse payability(@PathVariable Long bookingId) {
        BookingPayabilityResponse response =
                BookingPayabilityResponse.from(bookingService.findForPayability(bookingId));
        log.info("GET /bookings/{}/payability -> status={} expiresAt={} total={} {}",
                bookingId, response.status(), response.expiresAt(),
                response.totalAmount(), response.currency());
        return response;
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

    /**
     * Parse the {@code source} param to a {@link MovieDataSource} (case-insensitively), rendering an unknown
     * value as a coded 400 — the same discipline as the sort whitelist, never a leaked 500. We bind it as
     * a String and parse here (rather than letting Spring bind the enum) precisely so a bad value becomes
     * our {@code BOOKING_VALIDATION_ERROR} ProblemDetail instead of a generic framework type-mismatch 400.
     */
    private MovieDataSource parseSource(String source) {
        try {
            return MovieDataSource.valueOf(source.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BookingException(BookingErrorCode.BOOKING_VALIDATION_ERROR,
                    "Unknown source '" + source + "'. Valid values: composition, readmodel");
        }
    }
}
