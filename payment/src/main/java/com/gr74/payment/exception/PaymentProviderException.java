package com.gr74.payment.exception;

/**
 * Thrown when the downstream payment provider could not complete a charge attempt for an
 * <em>infrastructural</em> reason — unreachable, timed out, interrupted — as opposed to a normal
 * business {@code DECLINED} (which is a successful call with a negative answer, not an error).
 *
 * <p>Maps to {@link PaymentErrorCode#PAYMENT_PROVIDER_UNAVAILABLE} (HTTP 503) so the caller's saga
 * can tell "try again later" apart from "the card was declined".
 */
public class PaymentProviderException extends PaymentException {

    public PaymentProviderException(String message, Throwable cause) {
        super(PaymentErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, message, cause);
    }
}
