package com.gr74.payment.gateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Converts BigDecimal amounts to gateway integer minor units. Exponent comes from Currency;
 * UNNECESSARY rounding throws rather than silently rounding money.
 */
public final class MoneyConverter {

    private MoneyConverter() {
    }

    /** {@code 300.50 EGP -> 30050}. Throws if precision exceeds the currency. */
    public static long toMinorUnits(BigDecimal amount, String currency) {
        int exponent = fractionDigits(currency);
        return amount.movePointRight(exponent)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
    }

    /** Inverse: {@code 30050 EGP -> 300.50}. */
    public static BigDecimal fromMinorUnits(long minorUnits, String currency) {
        return BigDecimal.valueOf(minorUnits).movePointLeft(fractionDigits(currency));
    }

    /** Minor-unit digits for an ISO-4217 code. */
    private static int fractionDigits(String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        return Math.max(digits, 0);
    }
}
