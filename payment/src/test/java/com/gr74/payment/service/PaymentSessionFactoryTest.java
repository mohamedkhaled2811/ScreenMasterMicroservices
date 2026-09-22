package com.gr74.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gr74.payment.config.PaymentProps;
import com.gr74.payment.gateway.GatewayException;
import com.gr74.payment.gateway.GatewaySelector;
import com.gr74.payment.gateway.GatewaySession;
import com.gr74.payment.gateway.GatewaySessionRequest;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentGatewayType;

/**
 * Stranded-retry orchestration in {@link PaymentSessionFactory#completeStrandedSession}.
 */
@ExtendWith(MockitoExtension.class)
class PaymentSessionFactoryTest {

    private static final Long ATTEMPT_ID = 903L;
    private static final String IDEMPOTENCY_KEY = "pay-500-original-key";

    @Mock private PaymentWriter paymentWriter;
    @Mock private GatewaySelector gatewaySelector;

    private PaymentSessionFactory factory;

    @BeforeEach
    void setUp() {
        // PaymentProps is a plain record — real instance, no mock.
        factory = new PaymentSessionFactory(paymentWriter, gatewaySelector,
                new PaymentProps("http://localhost:8080"));
    }

    private PaymentWriter.StrandedRetry retryFacts() {
        return new PaymentWriter.StrandedRetry(ATTEMPT_ID, 500L, 1001L,
                "11111111-1111-1111-1111-111111111111",
                new BigDecimal("300.00"), "EGP", IDEMPOTENCY_KEY);
    }

    private PaymentGateway gatewayReturning(GatewaySession session) {
        PaymentGateway gateway = mock(PaymentGateway.class);
        given(gateway.createSession(any(GatewaySessionRequest.class))).willReturn(session);
        return gateway;
    }

    @Test
    @DisplayName("retry re-calls the gateway with the row's ORIGINAL idempotency key, then records")
    void retryReusesOriginalIdempotencyKey() {
        GatewaySession session = new GatewaySession("sbx_sess_2", "http://checkout/sbx_sess_2",
                Instant.now().plusSeconds(600));
        PaymentGateway gateway = gatewayReturning(session);
        given(gatewaySelector.select(PaymentGatewayType.SANDBOX, "EGP")).willReturn(gateway);
        given(paymentWriter.prepareStrandedRetry(ATTEMPT_ID, PaymentGatewayType.SANDBOX))
                .willReturn(retryFacts());
        PaymentAttempt recorded = mock(PaymentAttempt.class);
        given(paymentWriter.recordSession(ATTEMPT_ID, session)).willReturn(recorded);

        assertThat(factory.completeStrandedSession(ATTEMPT_ID, PaymentGatewayType.SANDBOX, "EGP"))
                .isSameAs(recorded);

        ArgumentCaptor<GatewaySessionRequest> call = ArgumentCaptor.forClass(GatewaySessionRequest.class);
        verify(gateway).createSession(call.capture());
        // The SAME key the first call carried — a fresh key here would risk a double charge
        // if the first call's session actually exists at the gateway.
        assertThat(call.getValue().idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(call.getValue().attemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(call.getValue().paymentId()).isEqualTo(500L);
        verify(paymentWriter).recordSession(ATTEMPT_ID, session);
    }

    @Test
    @DisplayName("retry adopts the REQUESTED gateway — switching mid-outage goes to the new one")
    void retryAdoptsRequestedGateway() {
        PaymentGateway paymob = gatewayReturning(
                new GatewaySession("pm_sess_1", "http://paymob/checkout",
                        Instant.now().plusSeconds(900)));
        given(gatewaySelector.select(PaymentGatewayType.PAYMOB, "EGP")).willReturn(paymob);
        given(paymentWriter.prepareStrandedRetry(ATTEMPT_ID, PaymentGatewayType.PAYMOB))
                .willReturn(retryFacts());
        given(paymentWriter.recordSession(any(), any())).willReturn(mock(PaymentAttempt.class));

        factory.completeStrandedSession(ATTEMPT_ID, PaymentGatewayType.PAYMOB, "EGP");

        // Guard 6 ran against the requested gateway, and adoption was committed before the call.
        verify(gatewaySelector).select(PaymentGatewayType.PAYMOB, "EGP");
        verify(paymentWriter).prepareStrandedRetry(ATTEMPT_ID, PaymentGatewayType.PAYMOB);
        verify(paymob).createSession(any(GatewaySessionRequest.class));
    }

    @Test
    @DisplayName("gateway down again: the failure propagates and nothing is recorded")
    void retryFailurePropagatesWithoutRecording() {
        PaymentGateway gateway = mock(PaymentGateway.class);
        given(gateway.createSession(any(GatewaySessionRequest.class))).willThrow(
                new GatewayException(PaymentGatewayType.SANDBOX, "still down"));
        given(gatewaySelector.select(PaymentGatewayType.SANDBOX, "EGP")).willReturn(gateway);
        given(paymentWriter.prepareStrandedRetry(ATTEMPT_ID, PaymentGatewayType.SANDBOX))
                .willReturn(retryFacts());

        assertThatThrownBy(
                () -> factory.completeStrandedSession(ATTEMPT_ID, PaymentGatewayType.SANDBOX, "EGP"))
                .isInstanceOf(GatewayException.class);

        // The row stays PENDING for the next retry: no terminal marking on evidence this thin,
        // and no session recorded from a call that threw.
        verify(paymentWriter, never()).recordSession(any(), any());
    }
}
