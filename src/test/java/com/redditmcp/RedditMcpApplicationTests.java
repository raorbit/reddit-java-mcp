package com.redditmcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the full application context (with the stdio transport disabled so the test does not block
 * on standard input) to verify that all beans wire together and the expected tools are registered.
 * This guards against wiring regressions that unit tests, which instantiate beans directly, miss.
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.server.stdio=false",
        "reddit.client-id=test-id",
        "reddit.client-secret=test-secret",
        "reddit.user-agent=java-reddit-mcp/test by tester"
})
class RedditMcpApplicationTests {

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Test
    void contextLoadsAndRegistersAllTools() {
        assertThat(toolCallbackProvider.getToolCallbacks()).hasSize(7);
    }
}
