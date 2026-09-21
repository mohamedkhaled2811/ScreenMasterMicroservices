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
 * Notification's AMQP wiring — the <b>consumer</b> side of Booking's booking-outcome stream
 * (BUILD_PLAN 4.3).
 *
 * <p>Notification owns the <em>queue</em> and its <em>bindings</em>; Booking owns the exchange and the
 * routing keys. This is the decoupling the broker gives us: Booking fires {@code BookingConfirmed} /
 * {@code BookingConfirmationRejected} into a shared topic exchange knowing nothing about who listens,
 * and Notification declares a queue and binds it to that exchange for the routing keys it cares about.
 * Adding this consumer required <b>zero changes to the producer</b> — that is what the topic exchange
 * buys.
 *
 * <p>The exchange name and routing keys are duplicated as string constants that must match Booking's
 * {@code RabbitConfig} exactly. They are a <b>shared contract</b>, not shared code — the two services
 * have no common module (database-per-service extends to no shared jar), so the coupling is
 * intentional and lives in one obvious place on each side. The {@link Jackson2JsonMessageConverter}
 * must also match Booking's so the JSON payload round-trips into
 * {@link com.gr74.notification.messaging.BookingConfirmedEvent}.
 */
@Configuration
public class RabbitConfig {

    /** Shared topic exchange — must equal Booking's {@code RabbitConfig.EXCHANGE}. */
    public static final String EXCHANGE = "screenmaster-exchange";

    /** Routing key Booking publishes {@code BookingConfirmed} with — must equal Booking's constant. */
    public static final String BOOKING_CONFIRMED_ROUTING_KEY = "booking-confirmed-key";

    /** Routing key Booking publishes {@code BookingConfirmationRejected} with — must equal Booking's constant. */
    public static final String BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY = "booking-confirmation-rejected-key";

    /**
     * Notification's own durable queue for the booking-outcome stream.
     *
     * <p>One queue, two bindings: both {@code booking-confirmed-key} and {@code booking-confirmation-rejected-key}
     * land here, and the listener dispatches on the received routing key — the {@code PaymentEventListener}
     * shape. A second consumer wanting the same events would declare its <em>own</em> queue (fan-out);
     * Booking never changes.
     *
     * <p><b>A queue name is deployed infrastructure.</b> Unlike a Java symbol, this string names a
     * <em>durable queue that exists in the broker</em>. Renaming it later makes Notification declare a
     * brand-new queue while the old one stays bound to the routing keys, silently accumulating the
     * booking events that nothing consumes, and stranding whatever was in flight. Pick once.
     */
    public static final String NOTIFICATION_BOOKING_EVENTS_QUEUE = "notification-booking-events-queue";

    /**
     * Where a message goes when this consumer can <em>never</em> succeed with it.
     *
     * <p>Sending an email is not always retryable. A user with no address in Keycloak, a malformed
     * payload, a template that cannot render — none of those improve by trying again, and with
     * {@code default-requeue-rejected: false} plus the retry/backoff in {@code application.yml}, a
     * message that exhausts its attempts is rejected rather than requeued into a hot loop.
     *
     * <p>Rejected is not the same as discarded. Binding the queue to this dead-letter exchange means
     * the failed message lands somewhere a human can look at it, with its original payload intact. A
     * ticket that could not be sent is a customer who paid and got nothing — that must be inspectable,
     * not a line in a log that rolled over. Browse it at the RabbitMQ console
     * ({@code http://localhost:15672}).
     */
    public static final String NOTIFICATION_DLX = "notification-dlx";

    /** The dead-letter queue itself — bound to {@link #NOTIFICATION_DLX} by {@link #deadLetterBinding}. */
    public static final String NOTIFICATION_DLQ = "notification-booking-events-dlq";

    @Bean
    public TopicExchange screenmasterExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Queue bookingEventsQueue() {
        // Durable so booking outcomes wait here while Notification is briefly down — a lost
        // BookingConfirmationRejected strands a customer's refund, so the queue (not just the
        // publisher) must survive.
        return QueueBuilder.durable(NOTIFICATION_BOOKING_EVENTS_QUEUE)
                .deadLetterExchange(NOTIFICATION_DLX)
                .deadLetterRoutingKey(NOTIFICATION_DLQ)
                .build();
    }

    /**
     * The dead-letter exchange. A {@code direct} exchange, not a topic: there is exactly one
     * destination and the routing key is a fixed string, so pattern matching would buy nothing.
     */
    @Bean
    public DirectExchange notificationDeadLetterExchange() {
        return new DirectExchange(NOTIFICATION_DLX);
    }

    /** Durable, because the whole point is that these messages outlive the failure that made them. */
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

    /** JSON (de)serialization for AMQP payloads — must mirror Booking's converter. */
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
     * inbound message body deserializes as JSON. We start from Boot's configurer so all the sensible
     * defaults (acks, concurrency from properties) are kept, and only override the converter.
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