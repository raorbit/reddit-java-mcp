package com.redditmcp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.redditmcp.config.RedditProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RedditAuthServiceTest {

    private static final String TOKEN_URL = "https://www.reddit.com/api/v1/access_token";

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
                new RedditAuthService(properties(), builder, new MutableClock(Instant.now()));

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

        MutableClock clock = new MutableClock(Instant.now());
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
                new RedditAuthService(properties(), builder, new MutableClock(Instant.now()));

        assertThat(service.getAccessToken()).isEqualTo("tok-1");
        // tok-1 is still valid, but forceRefresh must hit the endpoint regardless.
        assertThat(service.forceRefresh()).isEqualTo("tok-2");
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
                new RedditAuthService(properties(), builder, new MutableClock(Instant.now()));

        assertThat(service.getAccessToken()).isEqualTo("tok-1");
        server.verify();
    }
}
