package com.gr74.payment.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Payment's AMQP wiring — the <b>publisher</b> side of the booking saga (BUILD_PLAN 3.2).
 *
 * <p>Mirrors {@code catalog}'s {@code RabbitConfig} idiom: Payment owns the exchange and the
 * routing keys and deliberately declares <em>no queue and no binding</em> — a queue is the
 * consumer's concern. Booking declares {@code booking-payment-events-queue} and binds it to these
 * keys; a published event with no bound queue is simply dropped by the exchange, which is what
 * makes the routing-key contract safe to lock in before any consumer exists.
 *
 * <p>The {@link Jackson2JsonMessageConverter} must match the consumer's converter so the
 * {@code PaymentSucceededEvent}/{@code PaymentFailedEvent} records round-trip as JSON.
 */
@Configuration
public class RabbitConfig {

    /** Shared topic exchange (see {@code docs/concepts/rabbitmq.md}); Payment only ever publishes to it. */
    public static final String EXCHANGE = "screenmaster-exchange";

    /** Routing key for "a payment reached PAID" — Booking confirms off this. */
    public static final String PAYMENT_SUCCEEDED_ROUTING_KEY = "payment-succeeded-key";

    /** Routing key for "an attempt failed" — Booking mirrors it; the user may retry. */
    public static final String PAYMENT_FAILED_ROUTING_KEY = "payment-failed-key";

    /**
     * Routing key Booking publishes {@code BookingConfirmationRejected} with — must equal
     * Booking's {@code RabbitConfig.BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY} exactly. A shared
     * contract, not shared code (no common module), so the coupling lives in one obvious constant
     * on each side.
     */
    public static final String BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY =
            "booking-confirmation-rejected-key";

    /**
     * Payment's own durable queue for the booking-rejection stream (BUILD_PLAN 3.5) — the money
     * arrived too late and must come back.
     *
     * <p>Same deployed-infrastructure warning as Booking's queues: once this queue exists in the
     * broker with messages in it, renaming the constant strands them. Pick once.
     */
    public static final String BOOKING_EVENTS_QUEUE = "payment-booking-events-queue";

    @Bean
    public TopicExchange screenmasterExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Queue bookingEventsQueue() {
        // Durable so rejections wait here while Payment is briefly down — a lost rejection
        // strands a customer's refund, so the queue (not just the publisher) must survive.
        return new Queue(BOOKING_EVENTS_QUEUE, true);
    }

    @Bean
    public Binding bookingRejectionBinding(Queue bookingEventsQueue, TopicExchange screenmasterExchange) {
        return BindingBuilder.bind(bookingEventsQueue)
                .to(screenmasterExchange)
                .with(BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY);
    }

    /** JSON (de)serialization for AMQP payloads — must mirror the consumer's converter. */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /** A {@link RabbitTemplate} that uses the JSON converter for {@code convertAndSend}. */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    /**
     * The listener container factory {@code @RabbitListener} uses, wired to the JSON converter.
     * Kept from Boot's configurer so the sensible defaults (acks, concurrency) survive — only the
     * converter is overridden, mirroring Booking's factory so payloads round-trip identically.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        return factory;
    }
}
