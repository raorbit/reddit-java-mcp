package com.redditmcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Trimmed view of a subreddit (a {@code t5} thing), shaped for LLM consumption.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubredditInfo(
        String displayName,
        String title,
        long subscribers,
        String publicDescription,
        boolean over18,
        double createdUtc,
        String url) {
}
