package com.redditmcp.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.redditmcp.config.RedditProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Obtains and caches an app-only ("userless") OAuth bearer token for Reddit's free tier.
 *
 * <p>Uses the {@code client_credentials} grant against {@code https://www.reddit.com}. The token is
 * cached until shortly before it expires and shared across callers; a refresh is guarded so that
 * concurrent callers do not stampede the token endpoint.
 */
@Service
public class RedditAuthService {

    /** Base host for the OAuth token exchange (distinct from the {@code oauth.reddit.com} data API). */
    static final String AUTH_BASE_URL = "https://www.reddit.com";

    private static final String TOKEN_PATH = "/api/v1/access_token";

    /**
     * Refresh this far ahead of the real expiry so a token returned to a caller stays valid long
     * enough to be used on the next request.
     */
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);

    private final RedditProperties properties;
    private final RestClient restClient;
    private final Clock clock;

    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();
    private final Object refreshLock = new Object();

    public RedditAuthService(RedditProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, restClientBuilder, Clock.systemUTC());
    }

    RedditAuthService(RedditProperties properties, RestClient.Builder restClientBuilder, Clock clock) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(AUTH_BASE_URL).build();
        this.clock = clock;
    }

    /**
     * Returns a valid bearer token, fetching a new one only when the cached token is absent or
     * within the refresh skew of its expiry.
     */
    public String getAccessToken() {
        CachedToken current = cachedToken.get();
        if (isUsable(current)) {
            return current.value();
        }
        synchronized (refreshLock) {
            // Re-check: another thread may have refreshed while we waited for the lock.
            CachedToken latest = cachedToken.get();
            if (isUsable(latest)) {
                return latest.value();
            }
            return fetchAndCache().value();
        }
    }

    /**
     * Unconditionally fetches a fresh token, replacing any cached value. Used by the data API
     * client to recover from a 401 even when the cached token has not yet expired.
     */
    public String forceRefresh() {
        synchronized (refreshLock) {
            return fetchAndCache().value();
        }
    }

    private boolean isUsable(CachedToken token) {
        if (token == null) {
            return false;
        }
        Instant cutoff = token.expiresAt().minus(EXPIRY_SKEW);
        return Instant.now(clock).isBefore(cutoff);
    }

    private CachedToken fetchAndCache() {
        String credentials = properties.getClientId() + ":" + properties.getClientSecret();
        String basic = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        TokenResponse response = restClient.post()
                .uri(TOKEN_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .header(HttpHeaders.USER_AGENT, properties.getUserAgent())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials")
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Reddit token response did not contain an access_token");
        }

        Instant expiresAt = Instant.now(clock).plusSeconds(response.expiresIn());
        CachedToken token = new CachedToken(response.accessToken(), expiresAt);
        cachedToken.set(token);
        return token;
    }

    /** Immutable snapshot of the current token and the instant it expires. */
    private record CachedToken(String value, Instant expiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
