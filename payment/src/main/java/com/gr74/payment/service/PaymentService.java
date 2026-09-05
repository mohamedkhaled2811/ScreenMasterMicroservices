package com.gr74.payment.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.payment.client.BookingClient;
import com.gr74.payment.client.BookingPayability;
import com.gr74.payment.dto.CreatePaymentRequest;
import com.gr74.payment.dto.PaymentAttemptResponse;
import com.gr74.payment.dto.PaymentResponse;
import com.gr74.payment.dto.PaymentSessionResponse;
import com.gr74.payment.exception.BookingNotPayableException;
import com.gr74.payment.exception.ForbiddenBookingException;
import com.gr74.payment.exception.PaymentNotFoundException;
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentStatus;
import com.gr74.payment.repository.PaymentAttemptRepository;
import com.gr74.payment.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creating a checkout session — the write path behind {@code POST /payments}, and the same path
 * "Pay Again" takes.
 *
 * <p><b>The endpoint is idempotent at the business level.</b> Called repeatedly for one booking it
 * returns a usable checkout URL, and only opens a <em>new</em> gateway session when there isn't a
 * live one. That is why "Pay Again" is not a special case with its own code: it is this method,
 * called a second time, finding a lapsed attempt instead of a live one.
 *
 * <h2>Why this is not one transaction</h2>
 * The gateway call sits <b>outside</b> the transaction that creates the attempt, and that ordering is
 * the whole lesson (see {@code docs/concepts/payment-gateway-integration.md}). A database transaction
 * cannot span an external gateway: if we called the gateway inside one and the commit then failed,
 * the user could be charged for a session we have no record of, and no rollback could undo it. So we
 * <b>commit the attempt row first</b>, then call the gateway. If that call times out, the row already
 * exists as evidence and reconciliation can resolve it — the failure mode is a stranded PENDING
 * attempt, which is recoverable, rather than an untracked charge, which is not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final BookingClient bookingClient;
    private final PaymentWriter paymentWriter;
    private final PaymentSessionFactory sessionFactory;

    /**
     * Create or reuse a checkout session for a booking.
     *
     * <p>Runs the six guards, gets-or-creates the obligation, then either hands back a live attempt
     * or opens a new one.
     *
     * @return the session, and whether it was newly created (201) or reused (200)
     */
    public SessionOutcome createSession(CreatePaymentRequest request, String userId) {
        Instant now = Instant.now();

        // --- Guards 1-4: everything we cannot answer without Booking -----------------------------
        // Booking owns the booking, so this is where the amount comes from too. Fails closed: a
        // Booking outage throws 503 rather than letting us invent a price.
        BookingPayability booking = bookingClient.fetchPayability(request.bookingId());

        if (!userId.equals(booking.userId())) {
            // Checked even though a booking id is not secret: without it, anyone could enumerate ids
            // and open checkouts against other people's bookings.
            throw new ForbiddenBookingException(request.bookingId());
        }
        if (!booking.isPending()) {
            throw BookingNotPayableException.notPayable(request.bookingId(), booking.status());
        }
        if (!booking.isHoldLive(now)) {
            // The SEAT HOLD has lapsed — unrecoverable, unlike a lapsed gateway session. The seats
            // may already belong to someone else, so no new attempt may be created.
            throw BookingNotPayableException.expired(request.bookingId());
        }

        // --- Get or create the obligation --------------------------------------------------------
        // The obligation row is committed on its own (REQUIRES_NEW in PaymentWriter) — it must not
        // join a caller's transaction, or the attempt's FK to it would block on an uncommitted row.
        Payment payment = paymentWriter.getOrCreatePayment(booking);

        // --- Guard 5: already settled ------------------------------------------------------------
        if (payment.getStatus() == PaymentStatus.PAID) {
            throw BookingNotPayableException.alreadyPaid(request.bookingId());
        }
        if (payment.getStatus().isTerminal()) {
            throw BookingNotPayableException.notPayable(request.bookingId(),
                    "payment " + payment.getStatus());
        }

        // --- Reuse a live attempt if there is one ------------------------------------------------
        // This is what makes a double-clicked "Pay" button harmless, and what "Pay Again" hits when
        // the user simply reopens a still-valid checkout.
        Optional<PaymentAttempt> live = findLiveAttempt(payment.getId(), now);
        if (live.isPresent()) {
            PaymentAttempt reused = live.get();
            log.info("Reusing live attempt id={} paymentId={} gateway={} expiresAt={}",
                    reused.getId(), payment.getId(), reused.getGateway(), reused.getExpiresAt());
            return new SessionOutcome(PaymentSessionResponse.of(payment, reused), false);
        }

        // --- Guard 6 + create a new attempt ------------------------------------------------------
        // Guard 6 (gateway registered AND settles this currency) lives in the factory, next to the
        // gateway call it protects.
        PaymentAttempt created = sessionFactory.openNewSession(payment, request.gateway(), booking.currency());
        return new SessionOutcome(PaymentSessionResponse.of(payment, created), true);
    }

    /**
     * The one attempt that could still be paid: PENDING, with a checkout URL, and not past its
     * session deadline. Anything else needs a fresh session.
     */
    private Optional<PaymentAttempt> findLiveAttempt(Long paymentId, Instant now) {
        return attempts.findByPaymentIdAndStatus(paymentId, PaymentAttemptStatus.PENDING)
                .filter(attempt -> attempt.isLiveAt(now));
    }

    /** Read a payment with its attempt history. */
    @Transactional(readOnly = true)
    public PaymentResponse findById(Long paymentId, String userId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment " + paymentId + " not found"));
        if (!userId.equals(payment.getUserId())) {
            throw new ForbiddenBookingException(payment.getBookingId());
        }
        List<PaymentAttemptResponse> history = attempts
                .findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .map(PaymentAttemptResponse::of)
                .toList();
        return PaymentResponse.of(payment, history);
    }

    /** Read a payment by the booking it belongs to — how a client polls after checkout. */
    @Transactional(readOnly = true)
    public PaymentResponse findByBookingId(Long bookingId, String userId) {
        Payment payment = payments.findByBookingId(bookingId)
                .orElseThrow(() -> new PaymentNotFoundException("No payment for booking " + bookingId));
        return findById(payment.getId(), userId);
    }

    /**
     * The result of {@link #createSession}: the session plus whether a new attempt was opened, so the
     * controller can answer 201 (created) or 200 (reused) rather than guessing.
     */
    public record SessionOutcome(PaymentSessionResponse session, boolean created) {
    }
}
