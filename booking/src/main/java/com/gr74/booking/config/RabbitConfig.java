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
 * Booking's AMQP wiring: exchange, queues, bindings, and JSON message conversion.
 */
@Configuration
public class RabbitConfig {

    /** Shared topic exchange; must equal Catalog's exchange name. */
    public static final String EXCHANGE = "screenmaster-exchange";

    /** Routing key Catalog publishes MovieUpserted with; must match Catalog's constant. */
    public static final String MOVIE_UPSERTED_ROUTING_KEY = "movie-upserted-key";

    /** Routing key Payment publishes paid outcomes with; must match Payment's constant. */
    public static final String PAYMENT_SUCCEEDED_ROUTING_KEY = "payment-succeeded-key";

    /** Routing key Payment publishes failed attempts with; must match Payment's constant. */
    public static final String PAYMENT_FAILED_ROUTING_KEY = "payment-failed-key";

    /** Routing key Booking publishes {@code BookingConfirmed} with. */
    public static final String BOOKING_CONFIRMED_ROUTING_KEY = "booking-confirmed-key";

    /** Routing key Booking publishes {@code BookingConfirmationRejected} with. */
    public static final String BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY = "booking-confirmation-rejected-key";

    /**
     * Booking's durable queue for the movie projection read model.
     * Do not rename: it names a durable broker queue, and renaming strands its messages.
     */
    public static final String MOVIE_PROJECTIONS_QUEUE = "booking-movie-titles-queue";

    @Bean
    public TopicExchange screenmasterExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Queue movieProjectionsQueue() {
        // Durable so events wait while Booking is briefly down.
        return new Queue(MOVIE_PROJECTIONS_QUEUE, true);
    }

    @Bean
    public Binding movieProjectionsBinding(Queue movieProjectionsQueue, TopicExchange screenmasterExchange) {
        return BindingBuilder.bind(movieProjectionsQueue).to(screenmasterExchange).with(MOVIE_UPSERTED_ROUTING_KEY);
    }

    /**
     * Booking's durable queue for the payment-outcome stream.
     * Both payment routing keys land here; the listener dispatches on the received key.
     */
    public static final String PAYMENT_EVENTS_QUEUE = "booking-payment-events-queue";

    @Bean
    public Queue paymentEventsQueue() {
        // Durable so payment outcomes wait while Booking is briefly down.
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

    /** JSON (de)serialization for AMQP payloads; must match Catalog's converter. */
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

    /** Listener container factory using the JSON converter for inbound payloads. */
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
