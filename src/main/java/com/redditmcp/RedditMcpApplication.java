package com.redditmcp;

import com.redditmcp.config.RedditProperties;
import com.redditmcp.tools.RedditTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;

/**
 * Entry point for the read-only Reddit MCP server.
 *
 * <p>Runs as a Spring Boot application with the web stack disabled; the Spring AI MCP server
 * starter exposes the {@link RedditTools} methods to MCP clients over the stdio transport. Caching
 * of Reddit responses is activated by {@code @EnableCaching}, and the {@code reddit.*} configuration
 * is bound via {@code @EnableConfigurationProperties}.
 */
@SpringBootApplication
@EnableCaching
@EnableConfigurationProperties(RedditProperties.class)
public class RedditMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedditMcpApplication.class, args);
    }

    @Bean
    ToolCallbackProvider redditToolCallbacks(RedditTools redditTools) {
        return MethodToolCallbackProvider.builder().toolObjects(redditTools).build();
    }
}
