package com.gr74.payment.gateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Converts our {@link BigDecimal} amounts into the integer minor units most gateways want.
 *
 * <p>Gateways disagree on format: Stripe wants cents ({@code 30050}), Paymob wants piastres, others
 * want a decimal string ({@code "300.50"}). <b>That conversion belongs inside an adapter</b> — the
 * domain must never learn a gateway's wire format. This helper is shared by the adapters that need
 * minor units so the arithmetic is written once and tested once.
 *
 * <p>Two decisions worth naming:
 * <ul>
 *   <li><b>The exponent is read from {@link Currency}</b>, never hardcoded as {@code x100}. EGP and
 *       USD both have 2 minor digits, so this project would survive the shortcut — until JPY (0) or
 *       KWD (3). One line removes the whole class of bug.</li>
 *   <li><b>{@link RoundingMode#UNNECESSARY}</b> is deliberate: an amount that cannot be expressed
 *       exactly in minor units is a bug upstream, so we throw rather than round. Silently rounding
 *       money is how reconciliation drifts by cents forever.</li>
 * </ul>
 */
public final class MoneyConverter {

    private MoneyConverter() {
    }

    /**
     * {@code 300.50 EGP -> 30050}.
     *
     * @throws ArithmeticException if the amount has more precision than the currency allows
     */
    public static long toMinorUnits(BigDecimal amount, String currency) {
        int exponent = fractionDigits(currency);
        return amount.movePointRight(exponent)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
    }

    /** The inverse: {@code 30050 EGP -> 300.50}. Used when reading amounts back off a gateway. */
    public static BigDecimal fromMinorUnits(long minorUnits, String currency) {
        return BigDecimal.valueOf(minorUnits).movePointLeft(fractionDigits(currency));
    }

    /**
     * Minor-unit digits for an ISO-4217 code. Currencies with no minor unit in {@link Currency}
     * (returns {@code -1}, e.g. some funds codes) are treated as 0 rather than corrupting the shift.
     */
    private static int fractionDigits(String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        return Math.max(digits, 0);
    }
}
