package com.redditmcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.redditmcp.client.RedditApiClient;
import com.redditmcp.client.RedditApiException;
import com.redditmcp.client.RedditRateLimitException;
import com.redditmcp.model.CommentSummary;
import com.redditmcp.model.PostSummary;
import com.redditmcp.model.RedditUser;
import com.redditmcp.model.SubredditInfo;
import com.redditmcp.model.UntrustedRedditData;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RedditToolsTest {

    private final RedditApiClient client = Mockito.mock(RedditApiClient.class);
    private final RedditTools tools = new RedditTools(client);

    @Test
    void nullLimitClampsToDefaultTen() {
        List<PostSummary> expected = List.of(post("a"), post("b"));
        when(client.searchPosts(eq("query"), any(), any(), any(), anyInt())).thenReturn(expected);

        var result = tools.searchPosts("query", null, null, null, null);

        assertThat(result.data()).isSameAs(expected);
        assertThat(result.notice()).isEqualTo(UntrustedRedditData.NOTICE);
        assertThat(capturedSearchLimit()).isEqualTo(10);
    }

    @Test
    void hugeLimitClampsToMaxTwentyFive() {
        when(client.searchPosts(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        tools.searchPosts("query", null, null, null, 1000);

        assertThat(capturedSearchLimit()).isEqualTo(25);
    }

    @Test
    void zeroOrNegativeLimitClampsToMinOne() {
        when(client.searchPosts(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        tools.searchPosts("query", null, null, null, 0);
        assertThat(capturedSearchLimit()).isEqualTo(1);

        Mockito.reset(client);
        when(client.searchPosts(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        tools.searchPosts("query", null, null, null, -5);
        assertThat(capturedSearchLimit()).isEqualTo(1);
    }

    @Test
    void getPostCommentsClampsLimitAndDepth() {
        List<CommentSummary> expected = List.of(new CommentSummary("u", "hi", 1, 0d, 0, List.of()));
        when(client.getPostComments(eq("abc123"), anyInt(), anyInt())).thenReturn(expected);

        // depth null -> default 3
        var result = tools.getPostComments("abc123", null, null);
        assertThat(result.data()).isSameAs(expected);
        assertThat(capturedCommentsLimit()).isEqualTo(10);
        assertThat(capturedCommentsDepth()).isEqualTo(3);

        // depth 99 -> clamped to 10
        Mockito.reset(client);
        when(client.getPostComments(eq("abc123"), anyInt(), anyInt())).thenReturn(expected);
        tools.getPostComments("abc123", 5, 99);
        assertThat(capturedCommentsLimit()).isEqualTo(5);
        assertThat(capturedCommentsDepth()).isEqualTo(10);
    }

    // --- Error translation (M4) ---------------------------------------------

    @Test
    void searchPostsTranslatesRateLimitToRuntimeExceptionWithRetrySeconds() {
        when(client.searchPosts(any(), any(), any(), any(), anyInt()))
                .thenThrow(new RedditRateLimitException(30));

        assertThatThrownBy(() -> tools.searchPosts("query", null, null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Reddit rate limit exceeded; retry after 30 seconds.");
    }

    @Test
    void searchPostsSurfacesApiExceptionMessage() {
        when(client.searchPosts(any(), any(), any(), any(), anyInt()))
                .thenThrow(new RedditApiException("Reddit API request failed with HTTP 404"));

        assertThatThrownBy(() -> tools.searchPosts("query", null, null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Reddit API request failed with HTTP 404");
    }

    @Test
    void getSubredditPostsTranslatesRateLimitToRuntimeExceptionWithRetrySeconds() {
        when(client.getSubredditPosts(any(), any(), any(), anyInt()))
                .thenThrow(new RedditRateLimitException(12));

        assertThatThrownBy(() -> tools.getSubredditPosts("java", null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Reddit rate limit exceeded; retry after 12 seconds.");
    }

    @Test
    void getSubredditInfoSurfacesApiExceptionMessage() {
        when(client.getSubredditInfo(any()))
                .thenThrow(new RedditApiException("Reddit API request failed with HTTP 500"));

        assertThatThrownBy(() -> tools.getSubredditInfo("java"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Reddit API request failed with HTTP 500");
    }

    // --- Input validation (M5) ----------------------------------------------

    @Test
    void getSubredditPostsRejectsInvalidSubredditWithoutCallingClient() {
        assertThatThrownBy(() -> tools.getSubredditPosts("bad name!", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid subreddit name: bad name!");

        verify(client, never()).getSubredditPosts(any(), any(), any(), anyInt());
    }

    @Test
    void getSubredditInfoRejectsBlankSubredditWithoutCallingClient() {
        assertThatThrownBy(() -> tools.getSubredditInfo("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid subreddit name:    ");

        verify(client, never()).getSubredditInfo(any());
    }

    @Test
    void getUserInfoRejectsInvalidUsernameWithoutCallingClient() {
        assertThatThrownBy(() -> tools.getUserInfo("../x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid username: ../x");

        verify(client, never()).getUserInfo(any());
    }

    @Test
    void getPostCommentsRejectsInvalidPostIdWithoutCallingClient() {
        assertThatThrownBy(() -> tools.getPostComments("t3_abc", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid post id: t3_abc");

        verify(client, never()).getPostComments(any(), anyInt(), anyInt());
    }

    @Test
    void searchPostsRejectsBlankQueryWithoutCallingClient() {
        assertThatThrownBy(() -> tools.searchPosts("  ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing required search query.");

        verify(client, never()).searchPosts(any(), any(), any(), any(), anyInt());
    }

    @Test
    void searchSubredditsRejectsNullQueryWithoutCallingClient() {
        assertThatThrownBy(() -> tools.searchSubreddits(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing required search query.");

        verify(client, never()).searchSubreddits(any(), anyInt());
    }

    @Test
    void searchPostsRejectsInvalidSubredditWhenProvided() {
        assertThatThrownBy(() -> tools.searchPosts("query", "bad name!", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid subreddit name: bad name!");

        verify(client, never()).searchPosts(any(), any(), any(), any(), anyInt());
    }

    @Test
    void searchPostsAllowsNullSubredditForUnrestrictedSearch() {
        when(client.searchPosts(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        tools.searchPosts("query", null, null, null, null);

        ArgumentCaptor<String> subreddit = ArgumentCaptor.forClass(String.class);
        verify(client).searchPosts(eq("query"), subreddit.capture(), any(), any(), anyInt());
        assertThat(subreddit.getValue()).isNull();
    }

    @Test
    void searchPostsAllowsBlankSubredditForUnrestrictedSearch() {
        when(client.searchPosts(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        tools.searchPosts("query", "  ", null, null, null);

        verify(client).searchPosts(eq("query"), eq("  "), any(), any(), anyInt());
    }

    // --- Delegation coverage -------------------------------------------------

    @Test
    void getSubredditPostsForwardsArgsAndClampsLimit() {
        List<PostSummary> expected = List.of(post("a"));
        when(client.getSubredditPosts(eq("java"), eq("top"), eq("week"), anyInt()))
                .thenReturn(expected);

        var result = tools.getSubredditPosts("java", "top", "week", 1000);

        assertThat(result.data()).isSameAs(expected);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(client).getSubredditPosts(eq("java"), eq("top"), eq("week"), limit.capture());
        assertThat(limit.getValue()).isEqualTo(25);
    }

    @Test
    void getSubredditInfoDelegatesAndReturnsClientResult() {
        SubredditInfo expected =
                new SubredditInfo("java", "Java", 100L, "desc", false, 0d, "/r/java");
        when(client.getSubredditInfo("java")).thenReturn(expected);

        var result = tools.getSubredditInfo("java");

        assertThat(result.data()).isSameAs(expected);
        verify(client).getSubredditInfo("java");
    }

    @Test
    void searchSubredditsForwardsQueryAndClampsLimit() {
        List<SubredditInfo> expected =
                List.of(new SubredditInfo("java", "Java", 1L, "d", false, 0d, "/r/java"));
        when(client.searchSubreddits(eq("prog"), anyInt())).thenReturn(expected);

        var result = tools.searchSubreddits("prog", 0);

        assertThat(result.data()).isSameAs(expected);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(client).searchSubreddits(eq("prog"), limit.capture());
        assertThat(limit.getValue()).isEqualTo(1);
    }

    @Test
    void getUserInfoDelegatesAndReturnsClientResult() {
        RedditUser expected = new RedditUser("spez", 10L, 20L, 0d);
        when(client.getUserInfo("spez")).thenReturn(expected);

        var result = tools.getUserInfo("spez");

        assertThat(result.data()).isSameAs(expected);
        verify(client).getUserInfo("spez");
    }

    @Test
    void getUserPostsForwardsArgsAndClampsLimit() {
        List<PostSummary> expected = List.of(post("a"), post("b"));
        when(client.getUserPosts(eq("spez"), eq("hot"), anyInt())).thenReturn(expected);

        var result = tools.getUserPosts("spez", "hot", null);

        assertThat(result.data()).isSameAs(expected);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(client).getUserPosts(eq("spez"), eq("hot"), limit.capture());
        assertThat(limit.getValue()).isEqualTo(10);
    }

    // --- Capture helpers ----------------------------------------------------

    private int capturedSearchLimit() {
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        Mockito.verify(client)
                .searchPosts(any(), any(), any(), any(), limit.capture());
        return limit.getValue();
    }

    private int capturedCommentsLimit() {
        return capturedComments()[0];
    }

    private int capturedCommentsDepth() {
        return capturedComments()[1];
    }

    private int[] capturedComments() {
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> depth = ArgumentCaptor.forClass(Integer.class);
        Mockito.verify(client).getPostComments(any(), limit.capture(), depth.capture());
        return new int[] {limit.getValue(), depth.getValue()};
    }

    private static PostSummary post(String id) {
        return new PostSummary(id, "t", "a", "s", 0, 0, "/p/" + id, 0d, null, "http://x");
    }
}
