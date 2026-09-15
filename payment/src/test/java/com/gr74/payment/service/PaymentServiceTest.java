package com.gr74.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.gr74.payment.client.BookingClient;
import com.gr74.payment.client.BookingPayability;
import com.gr74.payment.dto.CreatePaymentRequest;
import com.gr74.payment.exception.BookingNotPayableException;
import com.gr74.payment.exception.ForbiddenBookingException;
import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.repository.PaymentAttemptRepository;
import com.gr74.payment.repository.PaymentRepository;

/**
 * The six guards and the reuse-or-create decision behind {@code POST /payments}.
 *
 * <p>These are the rules that stop a user being charged twice, charged for someone else's booking, or
 * charged for seats they no longer hold — so each guard gets its own test rather than being covered
 * incidentally by a happy path.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_USER = "22222222-2222-2222-2222-222222222222";
    private static final Long BOOKING_ID = 1001L;
    private static final BigDecimal AMOUNT = new BigDecimal("300.00");

    @Mock private PaymentRepository payments;
    @Mock private PaymentAttemptRepository attempts;
    @Mock private BookingClient bookingClient;
    @Mock private PaymentWriter paymentWriter;
    @Mock private PaymentSessionFactory sessionFactory;

    @InjectMocks private PaymentService service;

    private CreatePaymentRequest request;

    @BeforeEach
    void setUp() {
        request = new CreatePaymentRequest(BOOKING_ID, PaymentGatewayType.PAYMOB);
    }

    /** A booking that passes every guard: mine, PENDING, hold still live. */
    private BookingPayability payableBooking() {
        return new BookingPayability(BOOKING_ID, USER, "PENDING",
                Instant.now().plus(10, ChronoUnit.MINUTES), AMOUNT, "EGP");
    }

    /** A persisted Payment with an id, since the entity's id is normally set by the database. */
    private Payment persistedPayment() {
        Payment payment = new Payment(BOOKING_ID, USER, AMOUNT, "EGP");
        ReflectionTestUtils.setField(payment, "id", 500L);
        return payment;
    }

    private PaymentAttempt attempt(PaymentAttemptStatus status, Instant expiresAt, String checkoutUrl) {
        PaymentAttempt attempt = new PaymentAttempt(PaymentGatewayType.PAYMOB, "key-" + status);
        ReflectionTestUtils.setField(attempt, "id", 900L);
        ReflectionTestUtils.setField(attempt, "status", status);
        ReflectionTestUtils.setField(attempt, "expiresAt", expiresAt);
        ReflectionTestUtils.setField(attempt, "checkoutUrl", checkoutUrl);
        return attempt;
    }

    // ---------------------------------------------------------------------------------------------
    // Guards
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("guard 2: refuses to open a checkout for someone else's booking")
    void rejectsForeignBooking() {
        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(
                new BookingPayability(BOOKING_ID, OTHER_USER, "PENDING",
                        Instant.now().plus(10, ChronoUnit.MINUTES), AMOUNT, "EGP"));

        assertThatThrownBy(() -> service.createSession(request, USER))
                .isInstanceOf(ForbiddenBookingException.class);

        // Nothing was written, and no gateway was contacted.
        verify(paymentWriter, never()).getOrCreatePayment(any());
        verify(sessionFactory, never()).openNewSession(any(), any(), any());
    }

    @Test
    @DisplayName("guard 3: refuses a booking that is not PENDING")
    void rejectsNonPendingBooking() {
        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(
                new BookingPayability(BOOKING_ID, USER, "CANCELLED",
                        Instant.now().plus(10, ChronoUnit.MINUTES), AMOUNT, "EGP"));

        assertThatThrownBy(() -> service.createSession(request, USER))
                .isInstanceOf(BookingNotPayableException.class)
                .extracting(e -> ((PaymentException) e).errorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_BOOKING_NOT_PAYABLE);
    }

    @Test
    @DisplayName("guard 4: refuses once the SEAT HOLD has lapsed — the unrecoverable clock")
    void rejectsExpiredBooking() {
        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(
                new BookingPayability(BOOKING_ID, USER, "PENDING",
                        Instant.now().minus(1, ChronoUnit.MINUTES), AMOUNT, "EGP"));

        assertThatThrownBy(() -> service.createSession(request, USER))
                .isInstanceOf(BookingNotPayableException.class)
                .extracting(e -> ((PaymentException) e).errorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_BOOKING_EXPIRED);

        // Critical: no new attempt. The seats may already belong to someone else.
        verify(sessionFactory, never()).openNewSession(any(), any(), any());
    }

    @Test
    @DisplayName("guard 5: refuses to charge an already-paid booking")
    void rejectsAlreadyPaid() {
        Payment paid = persistedPayment();
        paid.markPaid();
        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(payableBooking());
        given(paymentWriter.getOrCreatePayment(any(BookingPayability.class))).willReturn(paid);

        assertThatThrownBy(() -> service.createSession(request, USER))
                .isInstanceOf(BookingNotPayableException.class)
                .extracting(e -> ((PaymentException) e).errorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PAID);

        verify(sessionFactory, never()).openNewSession(any(), any(), any());
    }

    // ---------------------------------------------------------------------------------------------
    // Reuse or create — the "Pay Again" behaviour
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("reuses a live session instead of opening a second one")
    void reusesLiveAttempt() {
        // A double-clicked "Pay" button, or a user reopening a checkout that is still valid.
        Payment payment = persistedPayment();
        PaymentAttempt live = attempt(PaymentAttemptStatus.PENDING,
                Instant.now().plus(5, ChronoUnit.MINUTES), "https://pay.example/live");

        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(payableBooking());
        given(paymentWriter.getOrCreatePayment(any(BookingPayability.class))).willReturn(payment);
        given(attempts.findByPaymentIdAndStatus(500L, PaymentAttemptStatus.PENDING))
                .willReturn(Optional.of(live));

        var outcome = service.createSession(request, USER);

        assertThat(outcome.created()).isFalse();                     // 200, not 201
        assertThat(outcome.session().checkoutUrl()).isEqualTo("https://pay.example/live");
        verify(sessionFactory, never()).openNewSession(any(), any(), any());
    }

    @Test
    @DisplayName("PAY AGAIN: a lapsed session yields a NEW attempt on the SAME payment")
    void payAgainCreatesNewAttemptOnSamePayment() {
        // The scenario the whole model exists for: session expired at 20:05, booking still good
        // until 20:15, user clicks "Pay Again".
        Payment payment = persistedPayment();
        PaymentAttempt lapsed = attempt(PaymentAttemptStatus.PENDING,
                Instant.now().minus(1, ChronoUnit.MINUTES), "https://pay.example/stale");
        PaymentAttempt fresh = attempt(PaymentAttemptStatus.PENDING,
                Instant.now().plus(10, ChronoUnit.MINUTES), "https://pay.example/fresh");
        ReflectionTestUtils.setField(fresh, "id", 901L);
        payment.addAttempt(fresh);

        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(payableBooking());
        given(paymentWriter.getOrCreatePayment(any(BookingPayability.class))).willReturn(payment);
        given(attempts.findByPaymentIdAndStatus(500L, PaymentAttemptStatus.PENDING))
                .willReturn(Optional.of(lapsed));   // present, but past its expiry -> not live
        given(sessionFactory.openNewSession(payment, PaymentGatewayType.PAYMOB, "EGP"))
                .willReturn(fresh);

        var outcome = service.createSession(request, USER);

        assertThat(outcome.created()).isTrue();                      // 201
        assertThat(outcome.session().attemptId()).isEqualTo(901L);
        // The obligation is unchanged — this is the point. No second Payment was created.
        assertThat(outcome.session().paymentId()).isEqualTo(500L);
        // One get-or-create, on the same obligation — no second Payment was created for the retry.
        verify(paymentWriter).getOrCreatePayment(any(BookingPayability.class));
    }

    @Test
    @DisplayName("a terminal attempt is not reused — a failed card gets a fresh session")
    void doesNotReuseTerminalAttempt() {
        Payment payment = persistedPayment();
        PaymentAttempt fresh = attempt(PaymentAttemptStatus.PENDING,
                Instant.now().plus(10, ChronoUnit.MINUTES), "https://pay.example/fresh");
        ReflectionTestUtils.setField(fresh, "id", 902L);

        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(payableBooking());
        given(paymentWriter.getOrCreatePayment(any(BookingPayability.class))).willReturn(payment);
        // No PENDING attempt at all — the previous one FAILED and is therefore terminal.
        given(attempts.findByPaymentIdAndStatus(500L, PaymentAttemptStatus.PENDING))
                .willReturn(Optional.empty());
        given(sessionFactory.openNewSession(payment, PaymentGatewayType.PAYMOB, "EGP"))
                .willReturn(fresh);

        assertThat(service.createSession(request, USER).created()).isTrue();
    }

    @Test
    @DisplayName("the amount comes from Booking, never from the client")
    void amountComesFromBooking() {
        Payment fresh = persistedPayment();
        PaymentAttempt attempt = attempt(PaymentAttemptStatus.PENDING,
                Instant.now().plus(10, ChronoUnit.MINUTES), "https://pay.example/new");

        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(payableBooking());
        given(paymentWriter.getOrCreatePayment(any(BookingPayability.class))).willReturn(fresh);
        given(attempts.findByPaymentIdAndStatus(any(), any())).willReturn(Optional.empty());
        given(sessionFactory.openNewSession(any(), any(), any())).willReturn(attempt);

        var outcome = service.createSession(request, USER);

        // The request body carries no amount at all; this value can only have come from Booking.
        assertThat(outcome.session().amount()).isEqualByComparingTo(AMOUNT);
        assertThat(outcome.session().currency()).isEqualTo("EGP");
    }

    @Test
    @DisplayName("the booking's currency selects the gateway, not the client's preference")
    void currencyFlowsFromBookingToGateway() {        Payment payment = persistedPayment();
        PaymentAttempt attempt = attempt(PaymentAttemptStatus.PENDING,
                Instant.now().plus(10, ChronoUnit.MINUTES), "https://pay.example/new");

        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(
                new BookingPayability(BOOKING_ID, USER, "PENDING",
                        Instant.now().plus(10, ChronoUnit.MINUTES), AMOUNT, "USD"));
        given(paymentWriter.getOrCreatePayment(any(BookingPayability.class))).willReturn(payment);
        given(attempts.findByPaymentIdAndStatus(any(), any())).willReturn(Optional.empty());
        given(sessionFactory.openNewSession(any(), any(), any())).willReturn(attempt);

        service.createSession(request, USER);

        // Guard 6 runs inside the factory, so what matters here is that the BOOKING's currency
        // reaches it — a client cannot smuggle a different one past the gateway check.
        verify(sessionFactory).openNewSession(payment, PaymentGatewayType.PAYMOB, "USD");
    }

    // ---------------------------------------------------------------------------------------------
    // Stranded-attempt retry — the outage path
    // ---------------------------------------------------------------------------------------------

    /** A PENDING attempt the gateway never answered: no session, no checkout, only evidence. */
    private PaymentAttempt strandedAttempt(PaymentGatewayType gateway) {
        PaymentAttempt stranded = new PaymentAttempt(gateway, "key-stranded-" + gateway);
        ReflectionTestUtils.setField(stranded, "id", 903L);
        ReflectionTestUtils.setField(stranded, "expiresAt",
                Instant.now().plus(15, ChronoUnit.MINUTES));   // the provisional deadline
        // checkoutUrl and gatewaySessionId stay null: the gateway never answered.
        return stranded;
    }

    @Test
    @DisplayName("RETRY: a stranded attempt is completed on its own row, not inserted beside it")
    void retryReusesStrandedRowInsteadOfInserting() {
        // First POST inserted this row, then the gateway threw before returning a session.
        Payment payment = persistedPayment();
        PaymentAttempt stranded = strandedAttempt(PaymentGatewayType.PAYMOB);
        PaymentAttempt completed = attempt(PaymentAttemptStatus.PENDING,
                Instant.now().plus(10, ChronoUnit.MINUTES), "https://pay.example/retried");
        ReflectionTestUtils.setField(completed, "id", 903L);

        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(payableBooking());
        given(paymentWriter.getOrCreatePayment(any(BookingPayability.class))).willReturn(payment);
        given(attempts.findByPaymentIdAndStatus(500L, PaymentAttemptStatus.PENDING))
                .willReturn(Optional.of(stranded));
        given(sessionFactory.completeStrandedSession(903L, PaymentGatewayType.PAYMOB, "EGP"))
                .willReturn(completed);

        var outcome = service.createSession(request, USER);

        assertThat(outcome.created()).isTrue();                      // 201 — a session was opened
        assertThat(outcome.session().attemptId()).isEqualTo(903L);   // on the SAME row
        // The one-live-attempt index would have rejected a second insert — so no insert happens.
        verify(sessionFactory, never()).openNewSession(any(), any(), any());
    }

    @Test
    @DisplayName("RETRY may switch gateways mid-outage — the stranded row adopts the requested one")
    void retryAdoptsRequestedGateway() {
        // Sandbox went down mid-checkout; the user picks Paymob for the retry.
        Payment payment = persistedPayment();
        PaymentAttempt stranded = strandedAttempt(PaymentGatewayType.SANDBOX);
        CreatePaymentRequest paymobRetry = new CreatePaymentRequest(BOOKING_ID, PaymentGatewayType.PAYMOB);
        PaymentAttempt completed = attempt(PaymentAttemptStatus.PENDING,
                Instant.now().plus(10, ChronoUnit.MINUTES), "https://pay.example/paymob");
        ReflectionTestUtils.setField(completed, "id", 903L);

        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(payableBooking());
        given(paymentWriter.getOrCreatePayment(any(BookingPayability.class))).willReturn(payment);
        given(attempts.findByPaymentIdAndStatus(500L, PaymentAttemptStatus.PENDING))
                .willReturn(Optional.of(stranded));
        given(sessionFactory.completeStrandedSession(903L, PaymentGatewayType.PAYMOB, "EGP"))
                .willReturn(completed);

        service.createSession(paymobRetry, USER);

        // The REQUESTED gateway reaches the factory — adoption happens there, on the old row.
        verify(sessionFactory).completeStrandedSession(903L, PaymentGatewayType.PAYMOB, "EGP");
        verify(sessionFactory, never()).openNewSession(any(), any(), any());
    }

    @Test
    @DisplayName("a lapsed session WITH a gateway answer still opens new — stranded path not taken")
    void lapsedRecordedSessionStillOpensNewAttempt() {
        // The Pay-Again path the stranded logic must not swallow: this attempt HAS a session
        // (checkoutUrl set), so it is lapsed-but-recorded, not stranded — isStranded() is false
        // and the request flows to openNewSession exactly as before.
        Payment payment = persistedPayment();
        PaymentAttempt lapsed = attempt(PaymentAttemptStatus.PENDING,
                Instant.now().minus(1, ChronoUnit.MINUTES), "https://pay.example/stale");
        PaymentAttempt fresh = attempt(PaymentAttemptStatus.PENDING,
                Instant.now().plus(10, ChronoUnit.MINUTES), "https://pay.example/fresh");
        ReflectionTestUtils.setField(fresh, "id", 901L);

        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(payableBooking());
        given(paymentWriter.getOrCreatePayment(any(BookingPayability.class))).willReturn(payment);
        given(attempts.findByPaymentIdAndStatus(500L, PaymentAttemptStatus.PENDING))
                .willReturn(Optional.of(lapsed));
        given(sessionFactory.openNewSession(payment, PaymentGatewayType.PAYMOB, "EGP"))
                .willReturn(fresh);

        assertThat(service.createSession(request, USER).session().attemptId()).isEqualTo(901L);

        verify(sessionFactory, never()).completeStrandedSession(any(), any(), any());
    }
}
