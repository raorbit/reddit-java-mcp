package com.redditmcp.tools;

import com.redditmcp.client.RedditApiClient;
import com.redditmcp.model.CommentSummary;
import com.redditmcp.model.PostSummary;
import com.redditmcp.model.RedditUser;
import com.redditmcp.model.SubredditInfo;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Read-only Reddit tools exposed to MCP clients.
 *
 * <p>Each method delegates to {@link RedditApiClient}, whose results are cached. User-supplied
 * {@code limit}/{@code depth} values are clamped to small, LLM-friendly ranges before being passed
 * to the client. DTOs and lists of DTOs are returned directly; Spring AI serializes them to JSON for
 * the tool response.
 */
@Component
public class RedditTools {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 25;

    private static final int DEFAULT_DEPTH = 3;
    private static final int MIN_DEPTH = 1;
    private static final int MAX_DEPTH = 10;

    private final RedditApiClient client;

    public RedditTools(RedditApiClient client) {
        this.client = client;
    }

    @Tool(description = """
            Search Reddit for posts matching a query. Searches across all of Reddit unless a \
            subreddit is given, in which case results are restricted to that subreddit. Returns a \
            list of post summaries (title, author, subreddit, score, comment count, permalink, and \
            a truncated body).""")
    public List<PostSummary> searchPosts(
            @ToolParam(description = "Search terms to match against posts.")
            String query,
            @ToolParam(description = "Optional subreddit name (without the r/ prefix) to restrict "
                    + "the search to.", required = false)
            String subreddit,
            @ToolParam(description = "Optional result ordering: relevance, hot, top, or new. "
                    + "Defaults to relevance.", required = false)
            String sort,
            @ToolParam(description = "Optional time window for 'top'/'relevance' results: hour, "
                    + "day, week, month, year, or all.", required = false)
            String timeFilter,
            @ToolParam(description = "Optional maximum number of posts to return (1-25, default "
                    + "10).", required = false)
            Integer limit) {
        return client.searchPosts(query, subreddit, sort, timeFilter, clampLimit(limit));
    }

    @Tool(description = """
            List posts from a specific subreddit. Use this to browse a subreddit's feed. Returns a \
            list of post summaries.""")
    public List<PostSummary> getSubredditPosts(
            @ToolParam(description = "Subreddit name (without the r/ prefix), e.g. 'java'.")
            String subreddit,
            @ToolParam(description = "Optional feed ordering: hot, new, top, or rising. Defaults "
                    + "to hot.", required = false)
            String sort,
            @ToolParam(description = "Optional time window, only meaningful when sort is 'top': "
                    + "hour, day, week, month, year, or all.", required = false)
            String timeFilter,
            @ToolParam(description = "Optional maximum number of posts to return (1-25, default "
                    + "10).", required = false)
            Integer limit) {
        return client.getSubredditPosts(subreddit, sort, timeFilter, clampLimit(limit));
    }

    @Tool(description = """
            Fetch the comment tree for a Reddit post. Returns top-level comments with nested \
            replies; each comment includes author, body, score, and nesting depth.""")
    public List<CommentSummary> getPostComments(
            @ToolParam(description = "The post id (the base-36 'id' from a post summary, without "
                    + "the t3_ prefix).")
            String postId,
            @ToolParam(description = "Optional maximum number of comments to return (1-25, default "
                    + "10).", required = false)
            Integer limit,
            @ToolParam(description = "Optional maximum reply nesting depth to traverse (1-10, "
                    + "default 3).", required = false)
            Integer depth) {
        return client.getPostComments(postId, clampLimit(limit), clampDepth(depth));
    }

    @Tool(description = """
            Get metadata about a subreddit: display name, title, subscriber count, public \
            description, NSFW flag, and creation time.""")
    public SubredditInfo getSubredditInfo(
            @ToolParam(description = "Subreddit name (without the r/ prefix), e.g. 'java'.")
            String subreddit) {
        return client.getSubredditInfo(subreddit);
    }

    @Tool(description = """
            Search for subreddits by name or topic. Returns a list of matching subreddits with \
            their metadata. Useful for discovering communities before browsing them.""")
    public List<SubredditInfo> searchSubreddits(
            @ToolParam(description = "Search terms to match against subreddit names and "
                    + "descriptions.")
            String query,
            @ToolParam(description = "Optional maximum number of subreddits to return (1-25, "
                    + "default 10).", required = false)
            Integer limit) {
        return client.searchSubreddits(query, clampLimit(limit));
    }

    @Tool(description = """
            Get a Reddit user's public profile: username, link karma, comment karma, and account \
            creation time.""")
    public RedditUser getUserInfo(
            @ToolParam(description = "Reddit username (without the u/ prefix).")
            String username) {
        return client.getUserInfo(username);
    }

    @Tool(description = """
            List posts submitted by a Reddit user. Returns a list of post summaries for that \
            user's submissions.""")
    public List<PostSummary> getUserPosts(
            @ToolParam(description = "Reddit username (without the u/ prefix).")
            String username,
            @ToolParam(description = "Optional ordering: new, hot, or top. Defaults to new.",
                    required = false)
            String sort,
            @ToolParam(description = "Optional maximum number of posts to return (1-25, default "
                    + "10).", required = false)
            Integer limit) {
        return client.getUserPosts(username, sort, clampLimit(limit));
    }

    // --- Input clamping -----------------------------------------------------

    /** Returns the default when {@code limit} is null, otherwise clamps it into {@code [1, 25]}. */
    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, limit));
    }

    /** Returns the default when {@code depth} is null, otherwise clamps it into {@code [1, 10]}. */
    private static int clampDepth(Integer depth) {
        if (depth == null) {
            return DEFAULT_DEPTH;
        }
        return Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, depth));
    }
}
