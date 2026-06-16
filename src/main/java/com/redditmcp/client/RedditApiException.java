package com.redditmcp.client;

/**
 * Raised for non-2xx Reddit data API responses other than 429 (which uses
 * {@link RedditRateLimitException}).
 */
public class RedditApiException extends RuntimeException {

    public RedditApiException(String message) {
        super(message);
    }

    public RedditApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
