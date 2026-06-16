package com.redditmcp.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Caffeine-backed response cache used by the Reddit data API client.
 *
 * <p>{@code @EnableCaching} is intentionally not declared here; it is applied on the application in
 * a later phase. When caching is disabled in configuration a {@link NoOpCacheManager} is supplied so
 * the {@code @Cacheable} annotations become inert without code changes.
 */
@Configuration
public class CacheConfig {

    /** Single cache holding mapped Reddit data API responses keyed by method + arguments. */
    public static final String REDDIT_GET_CACHE = "redditGet";

    @Bean
    public CacheManager cacheManager(RedditProperties props) {
        RedditProperties.Cache cache = props.getCache();
        if (!cache.isEnabled()) {
            return new NoOpCacheManager();
        }

        Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
                .expireAfterWrite(cache.getTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(cache.getMaxSize());

        CaffeineCacheManager manager = new CaffeineCacheManager(REDDIT_GET_CACHE);
        manager.setCaffeine(caffeine);
        return manager;
    }
}
