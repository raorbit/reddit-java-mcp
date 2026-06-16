package com.redditmcp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration bound from the {@code reddit.*} namespace.
 *
 * <p>Holds the Reddit API credentials and response-cache settings. Registered via
 * {@code @EnableConfigurationProperties} in a later phase, so this type carries no stereotype
 * annotation of its own. The credential and user-agent fields are {@code @NotBlank} so that a
 * misconfigured deployment fails fast at startup rather than on the first Reddit call.
 */
@Validated
@ConfigurationProperties(prefix = "reddit")
public class RedditProperties {

    @NotBlank
    private String clientId;

    @NotBlank
    private String clientSecret;

    @NotBlank
    private String userAgent;

    @Valid
    private Cache cache = new Cache();

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    /** Settings for the Caffeine-backed response cache. */
    public static class Cache {

        private boolean enabled = true;

        @Positive
        private long ttlSeconds = 60;

        @Positive
        private int maxSize = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }
    }
}
