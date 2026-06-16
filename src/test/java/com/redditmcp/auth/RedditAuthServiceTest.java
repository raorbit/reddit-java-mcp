package com.redditmcp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.redditmcp.config.RedditProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RedditAuthServiceTest {

    private static final String TOKEN_URL = "https://www.reddit.com/api/v1/access_token";

    /** Fixed instant the clock is seeded with so expiry arithmetic is deterministic. */
    private static final Instant FIXED_INSTANT = Instant.parse("2024-01-01T00:00:00Z");

    /** Clock whose instant can be advanced between steps to exercise expiry logic. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            this.instant = this.instant.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private RedditProperties properties() {
        RedditProperties props = new RedditProperties();
        props.setClientId("test-id");
        props.setClientSecret("test-secret");
        props.setUserAgent("java-reddit-mcp/test by tester");
        return props;
    }

    private String tokenJson(String token, long expiresIn) {
        return "{\"access_token\":\"" + token + "\",\"token_type\":\"bearer\",\"expires_in\":"
                + expiresIn + ",\"scope\":\"*\"}";
    }

    @Test
    void tokenFetchedOnceAndReused() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(tokenJson("tok-1", 3600), MediaType.APPLICATION_JSON));

        RedditAuthService service =
                new RedditAuthService(properties(), builder, new MutableClock(FIXED_INSTANT));

        assertThat(service.getAccessToken()).isEqualTo("tok-1");
        assertThat(service.getAccessToken()).isEqualTo("tok-1");
        server.verify();
    }

    @Test
    void refreshNearExpiry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(tokenJson("tok-1", 100), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(tokenJson("tok-2", 3600), MediaType.APPLICATION_JSON));

        MutableClock clock = new MutableClock(FIXED_INSTANT);
        RedditAuthService service = new RedditAuthService(properties(), builder, clock);

        assertThat(service.getAccessToken()).isEqualTo("tok-1");

        // Token lasts 100s; the 60s skew means it is considered stale after 40s.
        clock.advance(Duration.ofSeconds(50));
        assertThat(service.getAccessToken()).isEqualTo("tok-2");
        server.verify();
    }

    @Test
    void forceRefreshFetchesAnew() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(tokenJson("tok-1", 3600), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(tokenJson("tok-2", 3600), MediaType.APPLICATION_JSON));

        RedditAuthService service =
                new RedditAuthService(properties(), builder, new MutableClock(FIXED_INSTANT));

        assertThat(service.getAccessToken()).isEqualTo("tok-1");
        // tok-1 is still valid, but forceRefresh on the token we just used must fetch a new one.
        assertThat(service.forceRefresh("tok-1")).isEqualTo("tok-2");
        server.verify();
    }

    @Test
    void forceRefreshCollapsesWhenCacheAlreadyMoved() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(tokenJson("tok-1", 3600), MediaType.APPLICATION_JSON));

        RedditAuthService service =
                new RedditAuthService(properties(), builder, new MutableClock(FIXED_INSTANT));

        // Cache now holds tok-1; a caller that already saw an older token must not trigger a fetch.
        assertThat(service.getAccessToken()).isEqualTo("tok-1");
        assertThat(service.forceRefresh("stale-token")).isEqualTo("tok-1");
        server.verify();
    }

    @Test
    void tokenResponseWithoutAccessTokenThrows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        RedditAuthService service =
                new RedditAuthService(properties(), builder, new MutableClock(FIXED_INSTANT));

        assertThatThrownBy(service::getAccessToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Reddit token response did not contain an access_token");
        server.verify();
    }

    @Test
    void failedFetchIsNotCachedAndRetriesOnNextCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(tokenJson("tok-1", 3600), MediaType.APPLICATION_JSON));

        RedditAuthService service =
                new RedditAuthService(properties(), builder, new MutableClock(FIXED_INSTANT));

        // 401 surfaces as a sanitized IllegalStateException (status only, no body) and nothing is cached.
        assertThatThrownBy(service::getAccessToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
        // 500 propagates too; the previous failure left the cache empty so we hit the endpoint again.
        assertThatThrownBy(service::getAccessToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
        // Once the endpoint succeeds the token is finally cached and returned.
        assertThat(service.getAccessToken()).isEqualTo("tok-1");
        server.verify();
    }

    @Test
    void concurrentCallersShareSingleFetch() throws InterruptedException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Exactly one fetch is permitted: the double-checked lock must collapse the stampede.
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(tokenJson("tok-1", 3600), MediaType.APPLICATION_JSON));

        RedditAuthService service =
                new RedditAuthService(properties(), builder, new MutableClock(FIXED_INSTANT));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> tokens = ConcurrentHashMap.newKeySet();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    tokens.add(service.getAccessToken());
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        // Release all threads at once so they race for the refresh lock simultaneously.
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).isEmpty();
        assertThat(tokens).containsExactly("tok-1");
        // ExpectedCount.once() above asserts a single HTTP fetch occurred despite the stampede.
        server.verify();
    }

    @Test
    void requestShape() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String expectedBasic = Base64.getEncoder()
                .encodeToString("test-id:test-secret".getBytes(StandardCharsets.UTF_8));

        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + expectedBasic))
                .andExpect(header("User-Agent", "java-reddit-mcp/test by tester"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(Matchers.containsString("grant_type=client_credentials")))
                .andRespond(withSuccess(tokenJson("tok-1", 3600), MediaType.APPLICATION_JSON));

        RedditAuthService service =
                new RedditAuthService(properties(), builder, new MutableClock(FIXED_INSTANT));

        assertThat(service.getAccessToken()).isEqualTo("tok-1");
        server.verify();
    }
}
