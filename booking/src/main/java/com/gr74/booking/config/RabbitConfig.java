package com.gr74.booking.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
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

    /** JSON (de)serialization for AMQP payloads — must mirror Catalog's converter. */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
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
