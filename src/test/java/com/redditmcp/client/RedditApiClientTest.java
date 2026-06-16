package com.redditmcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.redditmcp.config.CacheConfig;
import com.redditmcp.config.RedditProperties;
import com.redditmcp.auth.RedditAuthService;
import com.redditmcp.model.CommentSummary;
import com.redditmcp.model.PostSummary;
import com.redditmcp.model.RedditUser;
import com.redditmcp.model.SubredditInfo;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Exercises {@link RedditApiClient} mapping logic and proves {@code @Cacheable} works through the
 * Spring bean proxy. The {@link MockRestServiceServer} is bound to the SAME {@code RestClient.Builder}
 * the client uses, so request expectations apply to the real HTTP path.
 */
class RedditApiClientTest {

    private static final String TOKEN = "test-token";

    private AnnotationConfigApplicationContext context;
    private MockRestServiceServer server;
    private RedditApiClient client;

    /**
     * Caching-enabled context: imports {@link CacheConfig} and supplies a mock auth service plus a
     * {@code RestClient.Builder} that the test binds {@link MockRestServiceServer} to.
     */
    @EnableCaching
    @Configuration
    @Import(CacheConfig.class)
    static class TestConfig {

        private final RestClient.Builder builder = RestClient.builder();

        @Bean
        RestClient.Builder restClientBuilder() {
            return builder;
        }

        /**
         * Binds the mock server to the SAME builder the client uses. Declared so it is initialised
         * BEFORE {@code redditApiClient}, ensuring the bound request factory is in place when the
         * client builds its {@code RestClient}.
         */
        @Bean
        MockRestServiceServer mockServer(RestClient.Builder b) {
            return MockRestServiceServer.bindTo(b).build();
        }

        @Bean
        RedditProperties redditProperties() {
            RedditProperties props = new RedditProperties();
            props.setUserAgent("java-reddit-mcp/test by tester");
            return props;
        }

        @Bean
        RedditAuthService redditAuthService() {
            RedditAuthService auth = mock(RedditAuthService.class);
            when(auth.getAccessToken()).thenReturn(TOKEN);
            return auth;
        }

        @Bean
        RedditApiClient redditApiClient(
                RedditProperties props,
                RedditAuthService auth,
                RestClient.Builder b,
                MockRestServiceServer ignored) {
            // Depend on mockServer so the bound factory is installed before the client builds.
            return new RedditApiClient(props, auth, b);
        }
    }

    private RedditAuthService authService;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        server = context.getBean(MockRestServiceServer.class);
        client = context.getBean(RedditApiClient.class);
        authService = context.getBean(RedditAuthService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void mapsSubredditPosts() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/hot")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(SUBREDDIT_LISTING, MediaType.APPLICATION_JSON));

        List<PostSummary> posts = client.getSubredditPosts("java", "hot", null, 5);

        assertThat(posts).hasSize(2);
        PostSummary first = posts.get(0);
        assertThat(first.title()).isEqualTo("First post");
        assertThat(first.author()).isEqualTo("alice");
        assertThat(first.score()).isEqualTo(123);
        assertThat(first.numComments()).isEqualTo(45);
        assertThat(first.permalink()).isEqualTo("/r/java/comments/abc/first_post/");
        assertThat(first.createdUtc()).isEqualTo(1700000000.0);
        assertThat(first.selftext()).isEqualTo("hello world");

        PostSummary second = posts.get(1);
        assertThat(second.title()).isEqualTo("Second post");
        assertThat(second.author()).isEqualTo("bob");
        server.verify();
    }

    @Test
    void mapsCommentsEnvelopeWithNesting() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/comments/abc")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(COMMENTS_ENVELOPE, MediaType.APPLICATION_JSON));

        List<CommentSummary> comments = client.getPostComments("abc", 50, 3);

        assertThat(comments).hasSize(1);
        CommentSummary top = comments.get(0);
        assertThat(top.author()).isEqualTo("alice");
        assertThat(top.body()).isEqualTo("top level comment");
        assertThat(top.depth()).isEqualTo(0);
        assertThat(top.replies()).hasSize(1);

        CommentSummary reply = top.replies().get(0);
        assertThat(reply.author()).isEqualTo("bob");
        assertThat(reply.body()).isEqualTo("a reply");
        assertThat(reply.depth()).isEqualTo(1);
        server.verify();
    }

    @Test
    void secondCallServedFromCache() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/hot")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(SUBREDDIT_LISTING, MediaType.APPLICATION_JSON));

        List<PostSummary> first = client.getSubredditPosts("java", "hot", null, 5);
        List<PostSummary> second = client.getSubredditPosts("java", "hot", null, 5);

        // Exactly one HTTP request was made; the second result came from the cache.
        assertThat(second).isEqualTo(first);
        server.verify();
    }

    @Test
    void retriesOnceAfter401ThenSucceeds() {
        when(authService.forceRefresh(TOKEN)).thenReturn("fresh-token");

        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/hot")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/hot")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(SUBREDDIT_LISTING, MediaType.APPLICATION_JSON));

        List<PostSummary> posts = client.getSubredditPosts("java", "hot", null, 5);

        assertThat(posts).hasSize(2);
        verify(authService, times(1)).forceRefresh(TOKEN);
        server.verify();
    }

    @Test
    void bothAttempts401ThrowsApiError() {
        when(authService.forceRefresh(TOKEN)).thenReturn("fresh-token");

        server.expect(ExpectedCount.twice(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/hot")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getSubredditPosts("java", "hot", null, 5))
                .isInstanceOf(RedditApiException.class)
                .hasMessage("Reddit API request failed with HTTP 401 after token refresh");
        verify(authService, times(1)).forceRefresh(TOKEN);
        server.verify();
    }

    @Test
    void rateLimitParsesResetHeaderWithCeil() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/hot")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("x-ratelimit-reset", "12.3"));

        assertThatThrownBy(() -> client.getSubredditPosts("java", "hot", null, 5))
                .isInstanceOf(RedditRateLimitException.class)
                .extracting(ex -> ((RedditRateLimitException) ex).getRetryAfterSeconds())
                .isEqualTo(13L);
        server.verify();
    }

    @Test
    void rateLimitMalformedResetHeaderFallsBackToFloor() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/hot")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("x-ratelimit-reset", "not-a-number"));

        assertThatThrownBy(() -> client.getSubredditPosts("java", "hot", null, 5))
                .isInstanceOf(RedditRateLimitException.class)
                .extracting(ex -> ((RedditRateLimitException) ex).getRetryAfterSeconds())
                .isEqualTo(1L);
        server.verify();
    }

    @Test
    void rateLimitMissingResetHeaderFallsBackToFloor() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/hot")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.getSubredditPosts("java", "hot", null, 5))
                .isInstanceOf(RedditRateLimitException.class)
                .extracting(ex -> ((RedditRateLimitException) ex).getRetryAfterSeconds())
                .isEqualTo(1L);
        server.verify();
    }

    @Test
    void rateLimitFallsBackToStandardRetryAfterHeader() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/hot")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "30"));

        assertThatThrownBy(() -> client.getSubredditPosts("java", "hot", null, 5))
                .isInstanceOf(RedditRateLimitException.class)
                .extracting(ex -> ((RedditRateLimitException) ex).getRetryAfterSeconds())
                .isEqualTo(30L);
        server.verify();
    }

    @Test
    void serverErrorThrowsApiErrorWithStatus() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/hot")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.getSubredditPosts("java", "hot", null, 5))
                .isInstanceOf(RedditApiException.class)
                .hasMessageContaining("500");
        server.verify();
    }

    @Test
    void notFoundOnNonAboutEndpointThrowsApiError() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/comments/abc")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getPostComments("abc", 50, 3))
                .isInstanceOf(RedditApiException.class)
                .hasMessageContaining("404");
        server.verify();
    }

    @Test
    void subredditInfoReturnsNullOn404() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/nope/about")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getSubredditInfo("nope")).isNull();
        server.verify();
    }

    @Test
    void userInfoReturnsNullOn404() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/user/nope/about")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getUserInfo("nope")).isNull();
        verify(authService, never()).forceRefresh(eq(TOKEN));
        server.verify();
    }

    @Test
    void commentRepliesCappedAtMaxPerNode() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/comments/abc")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(COMMENTS_MANY_REPLIES, MediaType.APPLICATION_JSON));

        List<CommentSummary> comments = client.getPostComments("abc", 50, 3);

        assertThat(comments).hasSize(1);
        // 7 t1 replies present, but MAX_REPLIES_PER_NODE caps the expansion at 5.
        assertThat(comments.get(0).replies()).hasSize(5);
        server.verify();
    }

    @Test
    void commentRepliesBeyondRequestedDepthAreDropped() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/comments/abc")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(COMMENTS_THREE_LEVELS, MediaType.APPLICATION_JSON));

        List<CommentSummary> comments = client.getPostComments("abc", 50, 1);

        assertThat(comments).hasSize(1);
        CommentSummary top = comments.get(0);
        assertThat(top.depth()).isEqualTo(0);
        // depth 1 reply is mapped, but its own (depth 2) replies are dropped.
        assertThat(top.replies()).hasSize(1);
        CommentSummary reply = top.replies().get(0);
        assertThat(reply.depth()).isEqualTo(1);
        assertThat(reply.replies()).isEmpty();
        server.verify();
    }

    @Test
    void longSelftextIsTruncatedWithEllipsis() {
        String longText = "x".repeat(600);
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/hot")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(singlePostListing(longText), MediaType.APPLICATION_JSON));

        List<PostSummary> posts = client.getSubredditPosts("java", "hot", null, 5);

        assertThat(posts).hasSize(1);
        String selftext = posts.get(0).selftext();
        assertThat(selftext).hasSize(503);
        assertThat(selftext).isEqualTo("x".repeat(500) + "...");
        server.verify();
    }

    @Test
    void selftextOfExactlyMaxLengthIsLeftIntact() {
        String exactText = "y".repeat(500);
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/hot")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(singlePostListing(exactText), MediaType.APPLICATION_JSON));

        List<PostSummary> posts = client.getSubredditPosts("java", "hot", null, 5);

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).selftext()).isEqualTo(exactText);
        server.verify();
    }

    @Test
    void mapsSubredditInfo() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/about")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("raw_json", "1"))
                .andRespond(withSuccess(SUBREDDIT_ABOUT, MediaType.APPLICATION_JSON));

        SubredditInfo info = client.getSubredditInfo("java");

        assertThat(info).isNotNull();
        assertThat(info.displayName()).isEqualTo("java");
        assertThat(info.title()).isEqualTo("The Java programming language");
        assertThat(info.subscribers()).isEqualTo(500000L);
        assertThat(info.publicDescription()).isEqualTo("All about Java");
        assertThat(info.over18()).isFalse();
        assertThat(info.url()).isEqualTo("/r/java/");
        server.verify();
    }

    @Test
    void mapsSearchSubreddits() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/subreddits/search")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("q", "java"))
                .andRespond(withSuccess(SUBREDDIT_SEARCH_LISTING, MediaType.APPLICATION_JSON));

        List<SubredditInfo> results = client.searchSubreddits("java", 10);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).displayName()).isEqualTo("java");
        assertThat(results.get(0).subscribers()).isEqualTo(500000L);
        assertThat(results.get(1).displayName()).isEqualTo("javahelp");
        server.verify();
    }

    @Test
    void mapsUserInfo() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/user/alice/about")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("raw_json", "1"))
                .andRespond(withSuccess(USER_ABOUT, MediaType.APPLICATION_JSON));

        RedditUser user = client.getUserInfo("alice");

        assertThat(user).isNotNull();
        assertThat(user.name()).isEqualTo("alice");
        assertThat(user.linkKarma()).isEqualTo(1000L);
        assertThat(user.commentKarma()).isEqualTo(2500L);
        assertThat(user.createdUtc()).isEqualTo(1500000000.0);
        server.verify();
    }

    @Test
    void mapsUserPostsWithSortQuery() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/user/alice/submitted")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("sort", "top"))
                .andExpect(queryParam("limit", "5"))
                .andRespond(withSuccess(SUBREDDIT_LISTING, MediaType.APPLICATION_JSON));

        List<PostSummary> posts = client.getUserPosts("alice", "top", 5);

        assertThat(posts).hasSize(2);
        assertThat(posts.get(0).author()).isEqualTo("alice");
        assertThat(posts.get(1).author()).isEqualTo("bob");
        server.verify();
    }

    @Test
    void topSortPassesTimeFilterQuery() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/top")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("t", "week"))
                .andRespond(withSuccess(SUBREDDIT_LISTING, MediaType.APPLICATION_JSON));

        List<PostSummary> posts = client.getSubredditPosts("java", "top", "week", 5);

        assertThat(posts).hasSize(2);
        server.verify();
    }

    @Test
    void restrictedSearchSetsRestrictSrQuery() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/r/java/search")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("restrict_sr", "1"))
                .andExpect(queryParam("q", "streams"))
                .andRespond(withSuccess(SUBREDDIT_LISTING, MediaType.APPLICATION_JSON));

        List<PostSummary> posts = client.searchPosts("streams", "java", null, null, 5);

        assertThat(posts).hasSize(2);
        server.verify();
    }

    @Test
    void malformedCommentsEnvelopeReturnsEmptyList() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/comments/abc")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(MALFORMED_COMMENTS_ENVELOPE, MediaType.APPLICATION_JSON));

        List<CommentSummary> comments = client.getPostComments("abc", 50, 3);

        assertThat(comments).isEmpty();
        server.verify();
    }

    @Test
    void topLevelCommentsBoundedByLimit() {
        server.expect(ExpectedCount.once(),
                        requestTo(Matchers.startsWith("https://oauth.reddit.com/comments/abc")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(commentsEnvelopeWithTopLevel(6), MediaType.APPLICATION_JSON));

        List<CommentSummary> comments = client.getPostComments("abc", 2, 3);

        assertThat(comments).hasSize(2);
        server.verify();
    }

    /** Builds a [post, listing] comments envelope whose comment listing has {@code n} top-level t1 nodes. */
    private static String commentsEnvelopeWithTopLevel(int n) {
        StringBuilder children = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                children.append(",");
            }
            children.append("""
                    {
                      "kind": "t1",
                      "data": { "author": "u%d", "body": "comment %d", "score": 1, \
                    "created_utc": 1700000000.0, "replies": "" }
                    }
                    """.formatted(i, i));
        }
        return """
                [
                  { "kind": "Listing", "data": { "children": \
                [ { "kind": "t3", "data": { "id": "abc" } } ] } },
                  { "kind": "Listing", "data": { "children": [ %s ] } }
                ]
                """.formatted(children.toString());
    }

    private static String singlePostListing(String selftext) {
        return """
                {
                  "kind": "Listing",
                  "data": {
                    "children": [
                      {
                        "kind": "t3",
                        "data": {
                          "id": "abc",
                          "title": "First post",
                          "author": "alice",
                          "subreddit": "java",
                          "score": 1,
                          "num_comments": 0,
                          "permalink": "/r/java/comments/abc/first_post/",
                          "created_utc": 1700000000.0,
                          "selftext": "%s",
                          "url": "https://example.com/1"
                        }
                      }
                    ]
                  }
                }
                """.formatted(selftext);
    }

    private static final String SUBREDDIT_LISTING = """
            {
              "kind": "Listing",
              "data": {
                "children": [
                  {
                    "kind": "t3",
                    "data": {
                      "id": "abc",
                      "title": "First post",
                      "author": "alice",
                      "subreddit": "java",
                      "score": 123,
                      "num_comments": 45,
                      "permalink": "/r/java/comments/abc/first_post/",
                      "created_utc": 1700000000.0,
                      "selftext": "hello world",
                      "url": "https://example.com/1"
                    }
                  },
                  {
                    "kind": "t3",
                    "data": {
                      "id": "def",
                      "title": "Second post",
                      "author": "bob",
                      "subreddit": "java",
                      "score": 7,
                      "num_comments": 2,
                      "permalink": "/r/java/comments/def/second_post/",
                      "created_utc": 1700000100.0,
                      "selftext": "",
                      "url": "https://example.com/2"
                    }
                  }
                ]
              }
            }
            """;

    private static final String COMMENTS_ENVELOPE = """
            [
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    { "kind": "t3", "data": { "id": "abc", "title": "the post" } }
                  ]
                }
              },
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t1",
                      "data": {
                        "author": "alice",
                        "body": "top level comment",
                        "score": 10,
                        "created_utc": 1700000000.0,
                        "replies": {
                          "kind": "Listing",
                          "data": {
                            "children": [
                              {
                                "kind": "t1",
                                "data": {
                                  "author": "bob",
                                  "body": "a reply",
                                  "score": 3,
                                  "created_utc": 1700000050.0,
                                  "replies": ""
                                }
                              },
                              { "kind": "more", "data": { "count": 5 } }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            ]
            """;

    /** Top-level comment carrying seven {@code t1} replies; the client caps expansion at five. */
    private static final String COMMENTS_MANY_REPLIES = """
            [
              {
                "kind": "Listing",
                "data": { "children": [ { "kind": "t3", "data": { "id": "abc" } } ] }
              },
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t1",
                      "data": {
                        "author": "alice",
                        "body": "top level comment",
                        "score": 10,
                        "created_utc": 1700000000.0,
                        "replies": {
                          "kind": "Listing",
                          "data": {
                            "children": [
                              { "kind": "t1", "data": { "author": "r1", "body": "one", "replies": "" } },
                              { "kind": "t1", "data": { "author": "r2", "body": "two", "replies": "" } },
                              { "kind": "t1", "data": { "author": "r3", "body": "three", "replies": "" } },
                              { "kind": "t1", "data": { "author": "r4", "body": "four", "replies": "" } },
                              { "kind": "t1", "data": { "author": "r5", "body": "five", "replies": "" } },
                              { "kind": "t1", "data": { "author": "r6", "body": "six", "replies": "" } },
                              { "kind": "t1", "data": { "author": "r7", "body": "seven", "replies": "" } }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            ]
            """;

    /** A three-level comment tree used to prove replies beyond the requested depth are dropped. */
    private static final String COMMENTS_THREE_LEVELS = """
            [
              {
                "kind": "Listing",
                "data": { "children": [ { "kind": "t3", "data": { "id": "abc" } } ] }
              },
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t1",
                      "data": {
                        "author": "alice",
                        "body": "level 0",
                        "created_utc": 1700000000.0,
                        "replies": {
                          "kind": "Listing",
                          "data": {
                            "children": [
                              {
                                "kind": "t1",
                                "data": {
                                  "author": "bob",
                                  "body": "level 1",
                                  "created_utc": 1700000050.0,
                                  "replies": {
                                    "kind": "Listing",
                                    "data": {
                                      "children": [
                                        {
                                          "kind": "t1",
                                          "data": {
                                            "author": "carol",
                                            "body": "level 2",
                                            "created_utc": 1700000060.0,
                                            "replies": ""
                                          }
                                        }
                                      ]
                                    }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            ]
            """;

    private static final String SUBREDDIT_ABOUT = """
            {
              "kind": "t5",
              "data": {
                "display_name": "java",
                "title": "The Java programming language",
                "subscribers": 500000,
                "public_description": "All about Java",
                "over18": false,
                "created_utc": 1200000000.0,
                "url": "/r/java/"
              }
            }
            """;

    private static final String SUBREDDIT_SEARCH_LISTING = """
            {
              "kind": "Listing",
              "data": {
                "children": [
                  {
                    "kind": "t5",
                    "data": {
                      "display_name": "java",
                      "title": "The Java programming language",
                      "subscribers": 500000,
                      "public_description": "All about Java",
                      "over18": false,
                      "created_utc": 1200000000.0,
                      "url": "/r/java/"
                    }
                  },
                  {
                    "kind": "t5",
                    "data": {
                      "display_name": "javahelp",
                      "title": "Java help",
                      "subscribers": 50000,
                      "public_description": "Get Java help",
                      "over18": false,
                      "created_utc": 1300000000.0,
                      "url": "/r/javahelp/"
                    }
                  }
                ]
              }
            }
            """;

    private static final String USER_ABOUT = """
            {
              "kind": "t2",
              "data": {
                "name": "alice",
                "link_karma": 1000,
                "comment_karma": 2500,
                "created_utc": 1500000000.0
              }
            }
            """;

    /** A single-element array; the comments method requires at least two elements, so it yields []. */
    private static final String MALFORMED_COMMENTS_ENVELOPE = """
            [
              {
                "kind": "Listing",
                "data": { "children": [ { "kind": "t3", "data": { "id": "abc" } } ] }
              }
            ]
            """;
}
