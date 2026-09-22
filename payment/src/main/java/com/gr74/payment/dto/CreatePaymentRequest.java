package com.gr74.payment.dto;

import com.gr74.payment.model.PaymentGatewayType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code POST /payments}: booking plus gateway; the amount is read from Booking.
 *
 * @param bookingId which booking to pay for
 * @param gateway   which gateway to use; must be registered and able to settle the booking's currency
 */
public record CreatePaymentRequest(

        @NotNull
        @Positive
        Long bookingId,

        @NotNull
        PaymentGatewayType gateway) {
}
