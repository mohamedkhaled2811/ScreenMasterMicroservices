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
 * Creates or reuses a checkout session for a booking; idempotent per booking.
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
     * Creates or reuses a checkout session for a booking.
     *
     * @return the session, and whether it was newly created (201) or reused (200)
     */
    public SessionOutcome createSession(CreatePaymentRequest request, String userId) {
        Instant now = Instant.now();

        // Booking owns the booking and amount; a Booking outage fails closed with 503.
        BookingPayability booking = bookingClient.fetchPayability(request.bookingId());

        if (!userId.equals(booking.userId())) {
            throw new ForbiddenBookingException(request.bookingId());
        }
        if (!booking.isPending()) {
            throw BookingNotPayableException.notPayable(request.bookingId(), booking.status());
        }
        if (!booking.isHoldLive(now)) {
            // Lapsed seat hold is unrecoverable; no new attempt may be created.
            throw BookingNotPayableException.expired(request.bookingId());
        }

        // Committed on its own in PaymentWriter (REQUIRES_NEW).
        Payment payment = paymentWriter.getOrCreatePayment(booking);

        // Already settled.
        if (payment.getStatus() == PaymentStatus.PAID) {
            throw BookingNotPayableException.alreadyPaid(request.bookingId());
        }
        if (payment.getStatus().isTerminal()) {
            throw BookingNotPayableException.notPayable(request.bookingId(),
                    "payment " + payment.getStatus());
        }

        // Reuse a live attempt if present.
        Optional<PaymentAttempt> pending =
                attempts.findByPaymentIdAndStatus(payment.getId(), PaymentAttemptStatus.PENDING);
        if (pending.isPresent() && pending.get().isLiveAt(now)) {
            PaymentAttempt reused = pending.get();
            log.info("Reusing live attempt id={} paymentId={} gateway={} expiresAt={}",
                    reused.getId(), payment.getId(), reused.getGateway(), reused.getExpiresAt());
            return new SessionOutcome(PaymentSessionResponse.of(payment, reused), false);
        }

        if (pending.isPresent() && pending.get().isStranded()) {
            PaymentAttempt completed = sessionFactory.completeStrandedSession(
                    pending.get().getId(), request.gateway(), booking.currency());
            return new SessionOutcome(PaymentSessionResponse.of(payment, completed), true);
        }

        // Open a new attempt.
        PaymentAttempt created = sessionFactory.openNewSession(payment, request.gateway(), booking.currency());
        return new SessionOutcome(PaymentSessionResponse.of(payment, created), true);
    }

    /** Reads a payment with its attempt history. */
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

    /** Reads a payment by booking id. */
    @Transactional(readOnly = true)
    public PaymentResponse findByBookingId(Long bookingId, String userId) {
        Payment payment = payments.findByBookingId(bookingId)
                .orElseThrow(() -> new PaymentNotFoundException("No payment for booking " + bookingId));
        return findById(payment.getId(), userId);
    }

    /** Session plus whether a new attempt was opened (201) or one reused (200). */
    public record SessionOutcome(PaymentSessionResponse session, boolean created) {
    }
}
