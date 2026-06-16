package com.redditmcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Trimmed view of a Reddit link (a {@code t3} thing), shaped for LLM consumption.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PostSummary(
        String id,
        String title,
        String author,
        String subreddit,
        int score,
        int numComments,
        String permalink,
        double createdUtc,
        String selftext,
        String url) {
}
