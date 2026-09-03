package com.gr74.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Minor-unit conversion — the arithmetic every gateway adapter depends on.
 *
 * <p>Worth testing properly because it is the one place a rounding bug would silently cost real
 * money, and because the currency-aware exponent only pays off for currencies this project does not
 * use day to day (JPY, KWD) — exactly the case a hand test would skip.
 */
class MoneyConverterTest {

    @ParameterizedTest(name = "{0} {1} -> {2} minor units")
    @CsvSource({
            // The scenario's amount, in Paymob's piastres.
            "300.50, EGP, 30050",
            "300.00, EGP, 30000",
            // Stripe cents.
            "12.34,  USD, 1234",
            "0.01,   USD, 1",
            // JPY has NO minor unit: x100 would be a 100x overcharge.
            "500,    JPY, 500",
            // KWD has THREE: x100 would undercharge by 10x.
            "1.234,  KWD, 1234",
    })
    @DisplayName("converts using the currency's own exponent, not a hardcoded x100")
    void convertsPerCurrencyExponent(BigDecimal amount, String currency, long expected) {
        assertThat(MoneyConverter.toMinorUnits(amount, currency)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{2} minor units -> {0} {1}")
    @CsvSource({
            "300.50, EGP, 30050",
            "12.34,  USD, 1234",
            "500,    JPY, 500",
            "1.234,  KWD, 1234",
    })
    @DisplayName("round-trips back to the original amount")
    void roundTrips(BigDecimal amount, String currency, long minorUnits) {
        assertThat(MoneyConverter.fromMinorUnits(minorUnits, currency))
                .isEqualByComparingTo(amount);
    }

    @Test
    @DisplayName("refuses to silently round an amount with too much precision")
    void refusesToRound() {
        // 300.505 EGP cannot be expressed in piastres. Rounding it would be a cent of drift per
        // transaction, forever, and reconciliation would never balance — so we throw instead.
        assertThatThrownBy(() -> MoneyConverter.toMinorUnits(new BigDecimal("300.505"), "EGP"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("trailing zeros are not extra precision")
    void toleratesTrailingZeros() {
        // 300.5000 is the same money as 300.50; the scale is noise, not precision, so this must pass.
        assertThat(MoneyConverter.toMinorUnits(new BigDecimal("300.5000"), "EGP")).isEqualTo(30050);
    }
}
