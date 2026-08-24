package com.gr74.catalog.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Catalog's AMQP wiring — the <b>publisher</b> side of the CQRS read model (BUILD_PLAN 2.3, way B).
 *
 * <p>Catalog owns the exchange and the routing key; it deliberately declares <em>no queue and no
 * binding</em> — a queue is the consumer's concern, and Catalog must not know Booking exists. Booking
 * declares its own queue and binds it to this exchange (its {@code RabbitConfig}). This keeps the two
 * services decoupled at the broker: Catalog fires into an exchange, and whoever is interested binds a
 * queue to it. Reuses the shared {@code screenmaster-exchange} topic exchange the concept doc
 * establishes ({@code docs/concepts/rabbitmq.md}).
 *
 * <p>The {@link Jackson2JsonMessageConverter} makes the {@link com.gr74.catalog.event.MovieUpserted}
 * record travel as JSON (a DTO on the wire, never an entity) — the same converter choice as the
 * monolith, and it must match the consumer's converter so the payload round-trips.
 */
@Configuration
public class RabbitConfig {

    /** Shared topic exchange (see {@code docs/concepts/rabbitmq.md}); Catalog only ever publishes to it. */
    public static final String EXCHANGE = "screenmaster-exchange";

    /** Routing key for "a movie row was (re)written on the refresh path". */
    public static final String MOVIE_UPSERTED_ROUTING_KEY = "movie-upserted-key";

    @Bean
    public TopicExchange screenmasterExchange() {
        return new TopicExchange(EXCHANGE);
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
}
