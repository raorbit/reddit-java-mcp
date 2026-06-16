package com.redditmcp.config;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * Applies finite connect/read timeouts to every {@code RestClient} built from the auto-configured
 * {@link org.springframework.web.client.RestClient.Builder}.
 *
 * <p>Both the auth ({@code www.reddit.com}) and data ({@code oauth.reddit.com}) clients run their
 * requests synchronously on the calling thread, and the token fetch holds a refresh lock while it
 * runs. Without timeouts a stalled or hung Reddit response would block a tool call — and any caller
 * waiting on the token lock — indefinitely. The customizer is applied to the shared builder, so both
 * clients inherit the timeouts without changing their constructors.
 */
@Configuration
public class HttpClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    RestClientCustomizer redditRestClientCustomizer() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return builder -> builder.requestFactory(factory);
    }
}
