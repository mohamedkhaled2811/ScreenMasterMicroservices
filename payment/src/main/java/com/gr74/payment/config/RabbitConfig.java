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
 * AMQP exchange, routing keys, queues, and JSON converter for payment events.
 */
@Configuration
public class RabbitConfig {

    /** Shared topic exchange; Payment only publishes to it (except its own rejection queue). */
    public static final String EXCHANGE = "screenmaster-exchange";

    /** Routing key for paid payments; Booking confirms off this. */
    public static final String PAYMENT_SUCCEEDED_ROUTING_KEY = "payment-succeeded-key";

    /** Routing key for failed attempts; Booking mirrors it and the user may retry. */
    public static final String PAYMENT_FAILED_ROUTING_KEY = "payment-failed-key";

    /** Routing key Booking publishes rejections with; must match Booking's constant exactly. */
    public static final String BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY =
            "booking-confirmation-rejected-key";

    /** Payment's durable queue for booking rejections, which trigger auto-refunds. */
    public static final String BOOKING_EVENTS_QUEUE = "payment-booking-events-queue";

    @Bean
    public TopicExchange screenmasterExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Queue bookingEventsQueue() {
        return new Queue(BOOKING_EVENTS_QUEUE, true);
    }

    @Bean
    public Binding bookingRejectionBinding(Queue bookingEventsQueue, TopicExchange screenmasterExchange) {
        return BindingBuilder.bind(bookingEventsQueue)
                .to(screenmasterExchange)
                .with(BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY);
    }

    /** JSON converter for AMQP payloads; must match the consumer's converter. */
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

    /** Listener container factory wired to the JSON converter. */
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
