package com.gr74.notification.config;

import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gr74.notification.identity.KeycloakUserClient;

/**
 * Caches resolved user emails. Entries expire after write to bound staleness.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(NotificationProps props) {
        CaffeineCacheManager manager = new CaffeineCacheManager(KeycloakUserClient.EMAIL_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(props.identity().emailCacheTtl())
                .maximumSize(props.identity().emailCacheMaxSize()));
        // Reject unknown caches instead of creating unbounded entries.
        manager.setAllowNullValues(false);
        return manager;
    }
}
