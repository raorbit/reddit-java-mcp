package com.redditmcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Trimmed view of a Reddit comment (a {@code t1} thing), shaped for LLM consumption.
 *
 * <p>{@code replies} is a bounded subtree: the client caps both nesting depth and the number of
 * replies expanded per node so payloads stay small.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommentSummary(
        String author,
        String body,
        int score,
        double createdUtc,
        int depth,
        List<CommentSummary> replies) {
}
