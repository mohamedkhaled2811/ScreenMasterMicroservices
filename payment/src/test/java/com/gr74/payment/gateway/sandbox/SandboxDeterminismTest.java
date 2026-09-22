package com.gr74.payment.gateway.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.gr74.payment.gateway.GatewayPaymentStatus;
import com.gr74.payment.gateway.GatewayStatusQuery;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.SandboxCharge;
import com.gr74.payment.repository.SandboxChargeRepository;

/**
 * fetchStatus answers the recorded outcome, never re-draws.
 */
@SpringBootTest
class SandboxDeterminismTest {

    @Autowired private SandboxGateway gateway;
    @Autowired private SandboxChargeRepository charges;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from sandbox_charges");
    }

    @Test
    @DisplayName("fetchStatus returns the same recorded answer on every call")
    void fetchStatusIsStableAcrossCalls() {
        charges.saveAndFlush(new SandboxCharge("sbx_stable", "sbx_pay_1",
                PaymentAttemptStatus.SUCCEEDED, null));

        GatewayPaymentStatus first = gateway.fetchStatus(GatewayStatusQuery.of("sbx_stable", null));
        for (int i = 0; i < 10; i++) {
            GatewayPaymentStatus again = gateway.fetchStatus(GatewayStatusQuery.of("sbx_stable", null));
            assertThat(again).isEqualTo(first);
        }
        assertThat(first.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(first.gatewayPaymentId()).isEqualTo("sbx_pay_1");
    }

    @Test
    @DisplayName("a recorded decline stays a decline — success is never invented")
    void recordedDeclineNeverBecomesSuccess() {
        charges.saveAndFlush(new SandboxCharge("sbx_declined", "sbx_pay_2",
                PaymentAttemptStatus.FAILED, "simulated_decline"));

        for (int i = 0; i < 10; i++) {
            assertThat(gateway.fetchStatus(GatewayStatusQuery.of("sbx_declined", null)).status())
                    .isEqualTo(PaymentAttemptStatus.FAILED);
        }
    }

    @Test
    @DisplayName("a session nobody paid is PENDING, addressable by either id")
    void unpaidSessionIsPending() {
        assertThat(gateway.fetchStatus(GatewayStatusQuery.of("sbx_never_paid", null)).status())
                .isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(gateway.fetchStatus(GatewayStatusQuery.of(null, "sbx_never_paid")).status())
                .isEqualTo(PaymentAttemptStatus.PENDING);
    }

    @Test
    @DisplayName("the sandbox payment id resolves to the same ledger row as the session id")
    void paymentIdLookupMatchesSessionLookup() {
        charges.saveAndFlush(new SandboxCharge("sbx_both", "sbx_pay_9",
                PaymentAttemptStatus.SUCCEEDED, null));

        assertThat(gateway.fetchStatus(GatewayStatusQuery.of(null, "sbx_pay_9")))
                .isEqualTo(gateway.fetchStatus(GatewayStatusQuery.of("sbx_both", null)));
    }
}
