# reddit-java-mcp

A read-only [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server for Reddit, written in Java with Spring Boot and the Spring AI MCP server starter. It exposes a set of tools for browsing and searching public Reddit data, intended to be launched locally by an MCP client (such as Claude Desktop) as a subprocess over the stdio transport.

The server reads public Reddit data through Reddit's free API tier using app-only OAuth. There is no user login, and the server performs no posting, commenting, or voting — it is read-only by design.

## Features

- Search posts across Reddit or within a specific subreddit.
- Browse a subreddit's feed (hot, new, top, rising).
- Fetch a post's comment tree with nested replies.
- Look up subreddit metadata and search for subreddits by name or topic.
- Look up a user's public profile and their submitted posts.
- Token and response caching to stay well within Reddit's rate limits.

## Requirements

- **JDK 21 or newer** (Spring Boot 3.5.x supports Java 17–25).
- **Maven** to build.
- A registered Reddit app. As of Reddit's Responsible Builder Policy (November 2025), all API access requires a registered and approved app, even for personal or hobby use.

## Reddit app setup

1. Go to <https://www.reddit.com/prefs/apps> and create a new app.
2. Choose the **script** app type.
3. Note the **client id** (shown directly under the app name) and the **secret**.

The server uses the OAuth `client_credentials` grant to obtain an app-only ("userless") token for reading public data. No Reddit user account is signed in, and no user-level permissions are requested.

## Configuration

Configuration is supplied through environment variables. Never commit secrets to source control.

| Variable | Description |
| --- | --- |
| `REDDIT_CLIENT_ID` | The script app's client id. |
| `REDDIT_CLIENT_SECRET` | The script app's secret. |
| `REDDIT_USER_AGENT` | A descriptive User-Agent string that Reddit requires, e.g. `java-reddit-mcp/0.1.0 by <your-reddit-username>`. |

`REDDIT_USER_AGENT` defaults to `java-reddit-mcp/0.1.0 by unknown` if unset, but you should set it to something that identifies you. These variables map to the `reddit.client-id`, `reddit.client-secret`, and `reddit.user-agent` properties in `application.properties`.

## Build

Build the project and produce a runnable jar (this also runs the tests):

```bash
mvn clean package
```

The output jar is written to `target/reddit-java-mcp-0.1.0.jar`.

## Running

The server speaks MCP over **stdio**: it reads JSON-RPC requests on stdin and writes responses on stdout. Because stdout is reserved for the protocol, application logs are written to `logs/reddit-mcp.log` (configured in `logback-spring.xml`).

It is normally launched by an MCP client rather than run by hand. To run it directly with the three environment variables set:

```bash
java -jar target/reddit-java-mcp-0.1.0.jar
```

### Registering with Claude Desktop

Add an entry to your `claude_desktop_config.json`. On Windows, backslashes in the jar path must be escaped:

```json
{
  "mcpServers": {
    "reddit": {
      "command": "java",
      "args": ["-jar", "C:\\Users\\raorb\\Projects\\reddit-java-mcp\\target\\reddit-java-mcp-0.1.0.jar"],
      "env": {
        "REDDIT_CLIENT_ID": "your-client-id",
        "REDDIT_CLIENT_SECRET": "your-client-secret",
        "REDDIT_USER_AGENT": "java-reddit-mcp/0.1.0 by your-username"
      }
    }
  }
}
```

Restart Claude Desktop after editing the config so it picks up the new server.

## Tools

All `limit` values are clamped to the range **1–25** (default **10**). The comment `depth` value is clamped to **1–10** (default **3**). Parameters marked optional may be omitted.

| Tool | Description |
| --- | --- |
| `searchPosts(query, subreddit?, sort?, timeFilter?, limit?)` | Search posts across Reddit, or within `subreddit` if given. `sort`: `relevance` \| `hot` \| `top` \| `new`. `timeFilter`: `hour` \| `day` \| `week` \| `month` \| `year` \| `all`. |
| `getSubredditPosts(subreddit, sort?, timeFilter?, limit?)` | List a subreddit's feed. `sort`: `hot` \| `new` \| `top` \| `rising` (default `hot`). `timeFilter` applies when `sort` is `top`. |
| `getPostComments(postId, limit?, depth?)` | Fetch a post's comment tree — top-level comments with nested replies. Each comment includes author, body, score, and depth. |
| `getSubredditInfo(subreddit)` | Subreddit metadata: display name, title, subscriber count, public description, NSFW flag, and creation time. |
| `searchSubreddits(query, limit?)` | Find subreddits by name or topic. |
| `getUserInfo(username)` | A user's public profile: name, link karma, comment karma, and account creation time. |
| `getUserPosts(username, sort?, limit?)` | Posts submitted by a user. `sort`: `new` \| `hot` \| `top`. |

## Caching & rate limits

Reddit's free tier allows roughly **100 requests per minute per client id**. Two cache layers keep usage well below that ceiling:

1. **Token cache** — the OAuth app-only token is cached and refreshed shortly before it expires, so most tool calls reuse an existing token.
2. **Response cache** — data API responses are cached with a short TTL (Caffeine, via Spring `@Cacheable`), so repeated identical tool calls are served from cache without hitting Reddit.

The response cache is configurable:

| Property | Default | Description |
| --- | --- | --- |
| `reddit.cache.enabled` | `true` | Enables or disables response caching. |
| `reddit.cache.ttl-seconds` | `60` | Time-to-live for cached responses, in seconds. |
| `reddit.cache.max-size` | `500` | Maximum number of cached entries. |

On an HTTP `429` response, the server surfaces a clear "rate limit; retry in N seconds" error. On an HTTP `401`, it refreshes the token and retries the request once.

## Security

- **Untrusted content / prompt injection:** every tool response is wrapped in an envelope with a
  fixed `notice` that flags the payload as untrusted, user-generated Reddit content (post and comment
  text, usernames, subreddit descriptions). That text can contain instructions crafted to manipulate
  an LLM, so the consuming model should treat all returned fields as data, never as commands. The
  server labels the content rather than altering it, preserving fidelity.
- **Input validation & least privilege:** subreddit/user identifiers and post ids are validated
  against Reddit's naming rules, `sort` values are checked against per-endpoint allowlists before
  they reach the request, and `limit`/`depth` are clamped to small ranges.
- **Resilience:** both HTTP clients use finite connect/read timeouts, response bodies are read with a
  hard size cap, and token-endpoint errors are surfaced without echoing upstream response bodies.
- **Credentials:** supplied only via environment variables and never logged; logs go to a file so the
  MCP stdio channel stays clean.

## Notes

- **Tech stack:** Spring Boot 3.5.x, Spring AI MCP server starter (stdio transport), Caffeine cache, Java 21.
- **Read-only by design:** the server performs no posting, commenting, voting, or any other write actions against Reddit.
