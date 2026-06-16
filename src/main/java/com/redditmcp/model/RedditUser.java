package com.redditmcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Trimmed view of a Reddit account (a {@code t2} thing), shaped for LLM consumption.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RedditUser(
        String name,
        long linkKarma,
        long commentKarma,
        double createdUtc) {
}
