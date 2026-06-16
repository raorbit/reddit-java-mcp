package com.redditmcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.redditmcp.auth.RedditAuthService;
import com.redditmcp.config.RedditProperties;
import com.redditmcp.model.CommentSummary;
import com.redditmcp.model.PostSummary;
import com.redditmcp.model.RedditUser;
import com.redditmcp.model.SubredditInfo;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * Read-only client over Reddit's data API ({@code https://oauth.reddit.com}).
 *
 * <p>Each public method maps Reddit's {@code Listing}/{@code thing} envelope into a trimmed,
 * LLM-friendly DTO and is annotated {@code @Cacheable} so that calls made through the Spring bean
 * proxy (from the Phase 4 tool layer) are cached. Because {@code @Cacheable} only takes effect on
 * proxied invocations, the cache annotations sit on these public methods rather than on the private
 * {@code getJson} helper they call directly.
 */
@Service
public class RedditApiClient {

    /** Package-private so tests can match absolute request URLs. */
    static final String DATA_BASE_URL = "https://oauth.reddit.com";

    private static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_REPLIES_PER_NODE = 5;

    private final RedditProperties properties;
    private final RedditAuthService authService;
    private final RestClient restClient;

    public RedditApiClient(
            RedditProperties properties,
            RedditAuthService authService,
            RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.authService = authService;
        this.restClient = restClientBuilder.baseUrl(DATA_BASE_URL).build();
    }

    @Cacheable(cacheNames = "redditGet",
            key = "'subredditPosts:' + #subreddit + ':' + #sort + ':' + #timeFilter + ':' + #limit")
    public List<PostSummary> getSubredditPosts(String subreddit, String sort, String timeFilter, int limit) {
        String resolvedSort = StringUtils.hasText(sort) ? sort : "hot";
        JsonNode listing = getJson(uri -> {
            uri.path("/r/{subreddit}/{sort}");
            uri.queryParam("limit", limit);
            uri.queryParam("raw_json", 1);
            if ("top".equals(resolvedSort) && StringUtils.hasText(timeFilter)) {
                uri.queryParam("t", timeFilter);
            }
            return uri.build(subreddit, resolvedSort);
        });
        return mapPosts(listing);
    }

    @Cacheable(cacheNames = "redditGet",
            key = "'searchPosts:' + #query + ':' + #subreddit + ':' + #sort + ':' + #timeFilter + ':' + #limit")
    public List<PostSummary> searchPosts(
            String query, String subreddit, String sort, String timeFilter, int limit) {
        boolean restrict = StringUtils.hasText(subreddit);
        JsonNode listing = getJson(uri -> {
            if (restrict) {
                uri.path("/r/{subreddit}/search");
                uri.queryParam("restrict_sr", 1);
            } else {
                uri.path("/search");
            }
            uri.queryParam("q", query);
            if (StringUtils.hasText(sort)) {
                uri.queryParam("sort", sort);
            }
            if (StringUtils.hasText(timeFilter)) {
                uri.queryParam("t", timeFilter);
            }
            uri.queryParam("limit", limit);
            uri.queryParam("raw_json", 1);
            return restrict ? uri.build(subreddit) : uri.build();
        });
        return mapPosts(listing);
    }

    @Cacheable(cacheNames = "redditGet",
            key = "'postComments:' + #postId + ':' + #limit + ':' + #depth")
    public List<CommentSummary> getPostComments(String postId, int limit, int depth) {
        JsonNode response = getJson(uri -> {
            uri.path("/comments/{postId}");
            uri.queryParam("limit", limit);
            uri.queryParam("depth", depth);
            uri.queryParam("raw_json", 1);
            return uri.build(postId);
        });

        // Response is a 2-element array: [ Listing(post t3), Listing(comment t1 forest) ].
        if (response == null || !response.isArray() || response.size() < 2) {
            return List.of();
        }
        JsonNode commentsListing = response.get(1);
        List<CommentSummary> result = new ArrayList<>();
        for (JsonNode data : listingChildren(commentsListing, "t1")) {
            result.add(mapComment(data, 0, depth));
        }
        return result;
    }

    @Cacheable(cacheNames = "redditGet", key = "'subredditInfo:' + #subreddit")
    public SubredditInfo getSubredditInfo(String subreddit) {
        JsonNode thing = getJson(uri -> {
            uri.path("/r/{subreddit}/about");
            uri.queryParam("raw_json", 1);
            return uri.build(subreddit);
        });
        JsonNode data = thing == null ? null : thing.path("data");
        return mapSubreddit(data);
    }

    @Cacheable(cacheNames = "redditGet", key = "'searchSubreddits:' + #query + ':' + #limit")
    public List<SubredditInfo> searchSubreddits(String query, int limit) {
        JsonNode listing = getJson(uri -> {
            uri.path("/subreddits/search");
            uri.queryParam("q", query);
            uri.queryParam("limit", limit);
            uri.queryParam("raw_json", 1);
            return uri.build();
        });
        List<SubredditInfo> result = new ArrayList<>();
        for (JsonNode data : listingChildren(listing, "t5")) {
            result.add(mapSubreddit(data));
        }
        return result;
    }

    @Cacheable(cacheNames = "redditGet", key = "'userInfo:' + #username")
    public RedditUser getUserInfo(String username) {
        JsonNode thing = getJson(uri -> {
            uri.path("/user/{username}/about");
            uri.queryParam("raw_json", 1);
            return uri.build(username);
        });
        JsonNode data = thing == null ? null : thing.path("data");
        return mapUser(data);
    }

    @Cacheable(cacheNames = "redditGet", key = "'userPosts:' + #username + ':' + #sort + ':' + #limit")
    public List<PostSummary> getUserPosts(String username, String sort, int limit) {
        JsonNode listing = getJson(uri -> {
            uri.path("/user/{username}/submitted");
            if (StringUtils.hasText(sort)) {
                uri.queryParam("sort", sort);
            }
            uri.queryParam("limit", limit);
            uri.queryParam("raw_json", 1);
            return uri.build(username);
        });
        return mapPosts(listing);
    }

    // --- HTTP ---------------------------------------------------------------

    /**
     * Performs an authenticated GET and returns the parsed JSON body. On a 401 the token is forcibly
     * refreshed and the request retried exactly once; a 429 raises {@link RedditRateLimitException}.
     */
    private JsonNode getJson(java.util.function.Function<UriBuilder, java.net.URI> uriFunction) {
        try {
            return doGet(uriFunction, authService.getAccessToken());
        } catch (UnauthorizedException ex) {
            return doGet(uriFunction, authService.forceRefresh());
        }
    }

    private JsonNode doGet(
            java.util.function.Function<UriBuilder, java.net.URI> uriFunction, String token) {
        return restClient.get()
                .uri(uriFunction::apply)
                .header(HttpHeaders.AUTHORIZATION, "bearer " + token)
                .header(HttpHeaders.USER_AGENT, properties.getUserAgent())
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == 401) {
                        throw new UnauthorizedException();
                    }
                    if (status.value() == 429) {
                        throw new RedditRateLimitException(parseRetryAfter(response.getHeaders()));
                    }
                    if (status.isError()) {
                        throw new RedditApiException(
                                "Reddit API request failed with HTTP " + status.value());
                    }
                    return response.bodyTo(JsonNode.class);
                });
    }

    private static long parseRetryAfter(HttpHeaders headers) {
        String reset = headers.getFirst("x-ratelimit-reset");
        if (reset != null) {
            try {
                return (long) Math.ceil(Double.parseDouble(reset.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 0L;
    }

    /** Sentinel used to unwind out of the {@code exchange} lambda so a 401 can be retried. */
    private static final class UnauthorizedException extends RuntimeException {
        UnauthorizedException() {
            super(null, null, false, false);
        }
    }

    // --- Mapping ------------------------------------------------------------

    private List<PostSummary> mapPosts(JsonNode listing) {
        List<PostSummary> result = new ArrayList<>();
        for (JsonNode data : listingChildren(listing, "t3")) {
            result.add(mapPost(data));
        }
        return result;
    }

    private PostSummary mapPost(JsonNode data) {
        return new PostSummary(
                text(data, "id"),
                text(data, "title"),
                text(data, "author"),
                text(data, "subreddit"),
                data.path("score").asInt(0),
                data.path("num_comments").asInt(0),
                text(data, "permalink"),
                data.path("created_utc").asDouble(0),
                trim(text(data, "selftext")),
                text(data, "url"));
    }

    private CommentSummary mapComment(JsonNode data, int depth, int maxDepth) {
        List<CommentSummary> replies = new ArrayList<>();
        if (depth < maxDepth) {
            JsonNode repliesNode = data.path("replies");
            // "replies" is either "" (none) or a Listing of more t1 comments.
            if (repliesNode.isObject()) {
                int count = 0;
                for (JsonNode child : listingChildren(repliesNode, "t1")) {
                    if (count >= MAX_REPLIES_PER_NODE) {
                        break;
                    }
                    replies.add(mapComment(child, depth + 1, maxDepth));
                    count++;
                }
            }
        }
        return new CommentSummary(
                text(data, "author"),
                trim(text(data, "body")),
                data.path("score").asInt(0),
                data.path("created_utc").asDouble(0),
                depth,
                replies);
    }

    private SubredditInfo mapSubreddit(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return null;
        }
        return new SubredditInfo(
                text(data, "display_name"),
                text(data, "title"),
                data.path("subscribers").asLong(0),
                text(data, "public_description"),
                data.path("over18").asBoolean(false),
                data.path("created_utc").asDouble(0),
                text(data, "url"));
    }

    private RedditUser mapUser(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return null;
        }
        return new RedditUser(
                text(data, "name"),
                data.path("link_karma").asLong(0),
                data.path("comment_karma").asLong(0),
                data.path("created_utc").asDouble(0));
    }

    // --- Helpers ------------------------------------------------------------

    /**
     * Given a {@code Listing} node, returns the {@code data} node of each child matching {@code kind}
     * ({@code more} children and other kinds are skipped).
     */
    private static List<JsonNode> listingChildren(JsonNode listing, String kind) {
        List<JsonNode> result = new ArrayList<>();
        if (listing == null) {
            return result;
        }
        JsonNode children = listing.path("data").path("children");
        if (!children.isArray()) {
            return result;
        }
        for (JsonNode child : children) {
            if (kind.equals(child.path("kind").asText())) {
                result.add(child.path("data"));
            }
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** Truncates long free-text (selftext/body) to keep payloads small. */
    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= MAX_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_TEXT_LENGTH) + "...";
    }
}
