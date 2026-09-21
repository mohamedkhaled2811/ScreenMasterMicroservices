package com.gr74.notification.exception;

/**
 * Delivery failed — SMTP refused the message, the mail host was unreachable, or the template would
 * not render.
 *
 * <p><b>This exception is meant to escape.</b> It is thrown by a {@code NotificationChannel}, passes
 * straight through {@code NotificationService}, and propagates out of the {@code @RabbitListener}.
 * That chain is load-bearing: the throw rolls back the transaction holding the {@code processed_events}
 * claim, the message is nacked, and RabbitMQ redelivers it with backoff. Catching it anywhere in
 * between would mark a never-sent ticket as processed — silently losing it while every dashboard
 * stayed green.
 *
 * <p>Always constructed with its {@code cause}: the useful detail (SMTP reply code, connection
 * refused, the missing template variable) lives in the underlying {@code MailException} or
 * {@code TemplateProcessingException}, and dropping it turns a five-second diagnosis into an hour.
 */
public class NotificationSendException extends NotificationException {

    public NotificationSendException(String message, Throwable cause) {
        super(NotificationErrorCode.NOTIFICATION_SEND_FAILED, message, cause);
    }
}
