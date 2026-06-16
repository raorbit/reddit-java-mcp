package com.redditmcp.client;

/**
 * Raised when Reddit responds with HTTP 429. Carries the suggested retry delay (in seconds) parsed
 * from the {@code x-ratelimit-reset} header when present.
 */
public class RedditRateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public RedditRateLimitException(long retryAfterSeconds) {
        super("Reddit rate limit hit; retry in " + retryAfterSeconds + " seconds");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
