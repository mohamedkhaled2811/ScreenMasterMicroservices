package com.gr74.notification.config;

import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gr74.notification.identity.KeycloakUserClient;

/**
 * Turns on caching and defines the one cache this service has: resolved user emails.
 *
 * <p><b>Why configured explicitly rather than by {@code spring.cache.caffeine.spec}.</b> That
 * property applies one spec to every cache in the application. Here the TTL and the bound are
 * meaningful choices about a specific thing — how stale an email address may be, and how much memory
 * a user-keyed map may occupy — so they are bound to {@link NotificationProps} and named, not
 * inherited from a global default that the next cache would silently share.
 *
 * <p><b>Why a local in-memory cache rather than Redis.</b> The data is cheap to re-fetch, per-instance
 * duplication is harmless, and a stale entry expires in minutes. Introducing a shared cache would add
 * a network dependency and an invalidation problem to save a handful of HTTP calls — the wrong trade.
 * If {@code --scale notification=4} ever made the lookup load matter, the fix is a shared cache, and
 * only then.
 *
 * <p><b>Expire AFTER WRITE, not after access.</b> A frequently-mailed user would otherwise refresh
 * their own entry forever and never pick up a changed address. Write-based expiry puts a hard ceiling
 * on staleness regardless of traffic.
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
        // Only the caches named above exist. A @Cacheable naming a cache we did not declare should
        // fail loudly at first use rather than silently creating an unbounded, never-expiring map.
        manager.setAllowNullValues(false);
        return manager;
    }
}
