package com.gr74.payment.dto;

import com.gr74.payment.model.PaymentGatewayType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code POST /payments}.
 *
 * <p><b>Note what is not here: the amount.</b> The client names a booking and a gateway; the amount
 * and currency are read from Booking, which owns them. A client that could state its own price would
 * be able to buy a 300 EGP ticket for 1 EGP.
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
