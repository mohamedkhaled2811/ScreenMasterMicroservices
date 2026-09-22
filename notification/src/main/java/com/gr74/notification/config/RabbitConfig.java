package com.gr74.notification.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
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
 * AMQP wiring for consuming booking outcomes. Owns the queue and bindings; Booking owns the exchange.
 */
@Configuration
public class RabbitConfig {

    /** Shared topic exchange; must match Booking. */
    public static final String EXCHANGE = "screenmaster-exchange";

    /** Routing key for BookingConfirmed; must match Booking. */
    public static final String BOOKING_CONFIRMED_ROUTING_KEY = "booking-confirmed-key";

    /** Routing key for BookingConfirmationRejected; must match Booking. */
    public static final String BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY = "booking-confirmation-rejected-key";

    /**
     * Durable queue for booking outcomes. Renaming strands the old queue in the broker.
     */
    public static final String NOTIFICATION_BOOKING_EVENTS_QUEUE = "notification-booking-events-queue";

    /** Dead-letter exchange for messages that can never succeed. */
    public static final String NOTIFICATION_DLX = "notification-dlx";

    /** Dead-letter queue bound to {@link #NOTIFICATION_DLX}. */
    public static final String NOTIFICATION_DLQ = "notification-booking-events-dlq";

    @Bean
    public TopicExchange screenmasterExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Queue bookingEventsQueue() {
        // Durable so outcomes wait here while Notification is briefly down.
        return QueueBuilder.durable(NOTIFICATION_BOOKING_EVENTS_QUEUE)
                .deadLetterExchange(NOTIFICATION_DLX)
                .deadLetterRoutingKey(NOTIFICATION_DLQ)
                .build();
    }

    /** Dead-letter exchange. Direct because there is a single fixed destination. */
    @Bean
    public DirectExchange notificationDeadLetterExchange() {
        return new DirectExchange(NOTIFICATION_DLX);
    }

    /** Durable so failed messages outlive the failure. */
    @Bean
    public Queue notificationDeadLetterQueue() {
        return QueueBuilder.durable(NOTIFICATION_DLQ).build();
    }

    @Bean
    public Binding deadLetterBinding(Queue notificationDeadLetterQueue,
            DirectExchange notificationDeadLetterExchange) {
        return BindingBuilder.bind(notificationDeadLetterQueue)
                .to(notificationDeadLetterExchange)
                .with(NOTIFICATION_DLQ);
    }

    @Bean
    public Binding bookingConfirmedBinding(Queue bookingEventsQueue, TopicExchange screenmasterExchange) {
        return BindingBuilder.bind(bookingEventsQueue).to(screenmasterExchange).with(BOOKING_CONFIRMED_ROUTING_KEY);
    }

    @Bean
    public Binding bookingConfirmationRejectedBinding(Queue bookingEventsQueue, TopicExchange screenmasterExchange) {
        return BindingBuilder.bind(bookingEventsQueue).to(screenmasterExchange)
                .with(BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY);
    }

    /** JSON (de)serialization for AMQP payloads. */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /** RabbitTemplate using the JSON converter. */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    /** Listener container factory using the JSON converter and Boot defaults. */
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