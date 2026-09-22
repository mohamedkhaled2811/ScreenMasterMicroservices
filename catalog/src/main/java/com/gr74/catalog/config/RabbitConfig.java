package com.gr74.catalog.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Catalog's AMQP wiring — declares the shared topic exchange and JSON converter.
 */
@Configuration
public class RabbitConfig {

    /** Shared topic exchange; Catalog only ever publishes to it. */
    public static final String EXCHANGE = "screenmaster-exchange";

    /** Routing key for "a movie row was (re)written on the refresh path". */
    public static final String MOVIE_UPSERTED_ROUTING_KEY = "movie-upserted-key";

    @Bean
    public TopicExchange screenmasterExchange() {
        return new TopicExchange(EXCHANGE);
    }

    /** JSON (de)serialization for AMQP payloads. */
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
