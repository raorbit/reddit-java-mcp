package com.redditmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the read-only Reddit MCP server.
 *
 * <p>Runs as a Spring Boot application with the web stack disabled; the Spring AI MCP server
 * starter exposes tools to MCP clients over the stdio transport. Tool registration, caching, and
 * configuration properties are wired in later phases.
 */
@SpringBootApplication
public class RedditMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedditMcpApplication.class, args);
    }
}
