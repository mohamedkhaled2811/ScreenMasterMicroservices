package com.gr74.booking.config;

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
 * Booking's AMQP wiring — the <b>consumer</b> side of the CQRS read model (BUILD_PLAN 2.3, way B).
 *
 * <p>Booking owns the <em>queue</em> and its <em>binding</em>; Catalog owns the exchange and the routing
 * key. This is the decoupling the broker gives us: Catalog fires {@code MovieUpserted} into a shared
 * topic exchange knowing nothing about who listens, and Booking declares a queue and binds it to that
 * exchange for the routing key it cares about. If a second consumer ever wants the same events it
 * declares its <em>own</em> queue and binds it too (fan-out) — Catalog never changes.
 *
 * <p>The exchange name and routing key are duplicated as string constants that must match Catalog's
 * {@code RabbitConfig}. They are a <b>shared contract</b>, not shared code — the two services have no
 * common module (database-per-service extends to no shared jar), so the coupling is intentional and
 * lives in one obvious place on each side. The {@link Jackson2JsonMessageConverter} must also match
 * Catalog's so the JSON payload round-trips into {@link com.gr74.booking.messaging.MovieUpsertedEvent}.
 */
@Configuration
public class RabbitConfig {

    /** Shared topic exchange — must equal Catalog's {@code RabbitConfig.EXCHANGE}. */
    public static final String EXCHANGE = "screenmaster-exchange";

    /** Routing key Catalog publishes MovieUpserted with — must equal Catalog's constant. */
    public static final String MOVIE_UPSERTED_ROUTING_KEY = "movie-upserted-key";

    /**
     * Routing key Payment's outbox relay publishes "a payment reached PAID" with — must equal
     * Payment's {@code RabbitConfig.PAYMENT_SUCCEEDED_ROUTING_KEY} exactly. A shared contract, not
     * shared code (no common module), so the coupling lives in one obvious constant on each side.
     */
    public static final String PAYMENT_SUCCEEDED_ROUTING_KEY = "payment-succeeded-key";

    /**
     * Routing key Payment's outbox relay publishes "an attempt failed" with — must equal Payment's
     * {@code RabbitConfig.PAYMENT_FAILED_ROUTING_KEY} exactly. Same shared-contract convention.
     */
    public static final String PAYMENT_FAILED_ROUTING_KEY = "payment-failed-key";

    /**
     * Routing key Booking publishes {@code BookingConfirmed} with. Locked now as the contract:
     * Phase 4 swaps the transport underneath (outbox) and Notification binds to this key without
     * Booking changing a line.
     */
    public static final String BOOKING_CONFIRMED_ROUTING_KEY = "booking-confirmed-key";

    /**
     * Routing key Booking publishes {@code BookingConfirmationRejected} with. Locked now as the
     * contract: Payment's 3.5 listener binds to this key and never changes.
     */
    public static final String BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY = "booking-confirmation-rejected-key";

    /**
     * Booking's own durable queue for the movie projection read model.
     *
     * <p><b>The value deliberately still says {@code movie-titles}.</b> Unlike every other name in this
     * package, this string does not merely identify a Java symbol — it names a <em>durable queue that
     * already exists in the broker</em>. Renaming it would make Booking declare a brand-new queue on the
     * next deploy while the old one stays bound to the routing key, silently accumulating
     * {@code MovieUpserted} events that nothing consumes. Nothing would throw; the read model would just
     * quietly stop updating for whatever was in flight, and the orphaned queue would linger until someone
     * deleted it by hand.
     *
     * <p>That is the lesson worth keeping: in a monolith a rename is safe by construction, but across a
     * broker boundary <b>a name is deployed infrastructure, and renaming it is a migration, not a
     * refactor</b>. Contrast {@code movie_projections}, the table this queue feeds: Booking owns it
     * privately (database-per-service), so renaming <em>it</em> was a single Liquibase changeset with
     * nothing to coordinate (see {@code 008-rename-movie-titles.yaml}).
     */
    public static final String MOVIE_PROJECTIONS_QUEUE = "booking-movie-titles-queue";

    @Bean
    public TopicExchange screenmasterExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Queue movieProjectionsQueue() {
        // Durable so events aren't lost while Booking is briefly down (they wait in the queue).
        return new Queue(MOVIE_PROJECTIONS_QUEUE, true);
    }

    @Bean
    public Binding movieProjectionsBinding(Queue movieProjectionsQueue, TopicExchange screenmasterExchange) {
        return BindingBuilder.bind(movieProjectionsQueue).to(screenmasterExchange).with(MOVIE_UPSERTED_ROUTING_KEY);
    }

    /**
     * Booking's own durable queue for the payment-outcome stream (BUILD_PLAN 3.3).
     *
     * <p>One queue, two bindings: both {@code payment-succeeded-key} and {@code payment-failed-key}
     * land here, and the listener dispatches on the received routing key. A second consumer wanting
     * the same events would declare its <em>own</em> queue — same fan-out rule as the movie queue.
     *
     * <p>Same deployed-infrastructure warning as {@link #MOVIE_PROJECTIONS_QUEUE}: once this queue
     * exists in the broker with messages in it, renaming the constant strands them. Pick once.
     */
    public static final String PAYMENT_EVENTS_QUEUE = "booking-payment-events-queue";

    @Bean
    public Queue paymentEventsQueue() {
        // Durable so payment outcomes wait here while Booking is briefly down — a lost
        // PaymentSucceeded costs real money, so the queue (not just the publisher) must survive.
        return new Queue(PAYMENT_EVENTS_QUEUE, true);
    }

    @Bean
    public Binding paymentSucceededBinding(Queue paymentEventsQueue, TopicExchange screenmasterExchange) {
        return BindingBuilder.bind(paymentEventsQueue).to(screenmasterExchange).with(PAYMENT_SUCCEEDED_ROUTING_KEY);
    }

    @Bean
    public Binding paymentFailedBinding(Queue paymentEventsQueue, TopicExchange screenmasterExchange) {
        return BindingBuilder.bind(paymentEventsQueue).to(screenmasterExchange).with(PAYMENT_FAILED_ROUTING_KEY);
    }

    /** JSON (de)serialization for AMQP payloads — must mirror Catalog's converter. */
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
     * The listener container factory {@code @RabbitListener} uses, wired to the JSON converter so the
     * inbound message body deserializes into a {@code MovieUpsertedEvent} record. We start from Boot's
     * configurer so all the sensible defaults (acks, concurrency from properties) are kept, and only
     * override the converter.
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
