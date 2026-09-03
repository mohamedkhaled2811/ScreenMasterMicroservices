package com.gr74.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gr74.payment.exception.CurrencyNotSupportedException;
import com.gr74.payment.exception.GatewayNotAvailableException;
import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.model.PaymentGatewayType;

/**
 * The registry and selector — the pair that replaces what would otherwise be an {@code if/else} chain.
 *
 * <p>Uses hand-written stub gateways rather than mocks: the thing under test is how a <em>set</em> of
 * adapters is assembled and filtered, so real objects with real {@code supportedCurrencies()} express
 * the intent better than stubbed method calls.
 */
class GatewayRegistryTest {

    private GatewayRegistry registry;
    private GatewaySelector selector;

    /** A minimal adapter that only answers the two questions the registry asks. */
    private static PaymentGateway stub(PaymentGatewayType type, String... currencies) {
        return new PaymentGateway() {
            @Override
            public PaymentGatewayType type() {
                return type;
            }

            @Override
            public Set<String> supportedCurrencies() {
                return Set.of(currencies);
            }

            @Override
            public GatewaySession createSession(GatewaySessionRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public GatewayPaymentStatus fetchStatus(String gatewayPaymentId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RefundResult refund(GatewayRefundRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public GatewayEvent parseAndVerifyWebhook(String rawPayload, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @BeforeEach
    void setUp() {
        // The real-world split: Paymob settles EGP, Stripe (test) settles USD, sandbox takes both.
        registry = new GatewayRegistry(List.of(
                stub(PaymentGatewayType.PAYMOB, "EGP"),
                stub(PaymentGatewayType.STRIPE, "USD"),
                stub(PaymentGatewayType.SANDBOX, "EGP", "USD")));
        selector = new GatewaySelector(registry);
    }

    @Test
    @DisplayName("builds itself from the injected adapter list")
    void registersEveryInjectedGateway() {
        assertThat(registry.available()).containsExactlyInAnyOrder(
                PaymentGatewayType.PAYMOB, PaymentGatewayType.STRIPE, PaymentGatewayType.SANDBOX);
    }

    @Test
    @DisplayName("an unconfigured gateway is simply absent, never listed-but-broken")
    void absentGatewayIsNotRegistered() {
        // Only SANDBOX is wired here — the shape of a deployment with no gateway credentials set.
        GatewayRegistry sandboxOnly = new GatewayRegistry(List.of(stub(PaymentGatewayType.SANDBOX, "EGP")));

        assertThat(sandboxOnly.available()).containsExactly(PaymentGatewayType.SANDBOX);
        assertThatThrownBy(() -> sandboxOnly.require(PaymentGatewayType.STRIPE))
                .isInstanceOf(GatewayNotAvailableException.class)
                .hasMessageContaining("STRIPE");
    }

    @Test
    @DisplayName("filters the picker by what each gateway can actually settle")
    void filtersByCurrency() {
        // This is the visible half of multi-currency: a client's picker never offers Paymob for USD.
        assertThat(registry.availableFor("EGP"))
                .containsExactlyInAnyOrder(PaymentGatewayType.PAYMOB, PaymentGatewayType.SANDBOX);
        assertThat(registry.availableFor("USD"))
                .containsExactlyInAnyOrder(PaymentGatewayType.STRIPE, PaymentGatewayType.SANDBOX);
        assertThat(registry.availableFor("GBP")).isEmpty();
    }

    @Test
    @DisplayName("selects a gateway that can settle the currency")
    void selectsMatchingGateway() {
        assertThat(selector.select(PaymentGatewayType.PAYMOB, "EGP").type())
                .isEqualTo(PaymentGatewayType.PAYMOB);
    }

    @Test
    @DisplayName("rejects a registered gateway that cannot settle this currency")
    void rejectsCurrencyMismatch() {
        // Guard 6: caught here rather than at the gateway, so the user finds out while choosing
        // instead of after being redirected to a checkout that cannot complete.
        assertThatThrownBy(() -> selector.select(PaymentGatewayType.PAYMOB, "USD"))
                .isInstanceOf(CurrencyNotSupportedException.class)
                .extracting(e -> ((CurrencyNotSupportedException) e).errorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_CURRENCY_NOT_SUPPORTED);
    }

    @Test
    @DisplayName("the mismatch error names the gateways that CAN take the currency")
    void mismatchSuggestsAlternatives() {
        assertThatThrownBy(() -> selector.select(PaymentGatewayType.PAYMOB, "USD"))
                .hasMessageContaining("STRIPE")
                .hasMessageContaining("SANDBOX");
    }

    @Test
    @DisplayName("an unregistered gateway is a 400, not a 503 — nothing is broken")
    void unregisteredGatewayIsClientError() {
        GatewayRegistry sandboxOnly = new GatewayRegistry(List.of(stub(PaymentGatewayType.SANDBOX, "EGP")));
        GatewaySelector sandboxSelector = new GatewaySelector(sandboxOnly);

        assertThatThrownBy(() -> sandboxSelector.select(PaymentGatewayType.PAYMOB, "EGP"))
                .isInstanceOf(GatewayNotAvailableException.class)
                .extracting(e -> ((GatewayNotAvailableException) e).errorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_GATEWAY_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("supports() answers both questions at once")
    void supportsChecksRegistrationAndCurrency() {
        assertThat(registry.supports(PaymentGatewayType.PAYMOB, "EGP")).isTrue();
        assertThat(registry.supports(PaymentGatewayType.PAYMOB, "USD")).isFalse();
    }
}
