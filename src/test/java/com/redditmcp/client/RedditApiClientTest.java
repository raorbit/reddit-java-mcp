package com.redditmcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.redditmcp.config.CacheConfig;
import com.redditmcp.config.RedditProperties;
import com.redditmcp.auth.RedditAuthService;
import com.redditmcp.model.CommentSummary;
import com.redditmcp.model.PostSummary;
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
import org.springframework.http.HttpMethod;
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

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        server = context.getBean(MockRestServiceServer.class);
        client = context.getBean(RedditApiClient.class);
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
}
