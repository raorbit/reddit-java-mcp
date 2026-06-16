package com.redditmcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.redditmcp.client.RedditApiClient;
import com.redditmcp.model.CommentSummary;
import com.redditmcp.model.PostSummary;
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

        List<PostSummary> result = tools.searchPosts("query", null, null, null, null);

        assertThat(result).isSameAs(expected);
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
        List<CommentSummary> result = tools.getPostComments("abc123", null, null);
        assertThat(result).isSameAs(expected);
        assertThat(capturedCommentsLimit()).isEqualTo(10);
        assertThat(capturedCommentsDepth()).isEqualTo(3);

        // depth 99 -> clamped to 10
        Mockito.reset(client);
        when(client.getPostComments(eq("abc123"), anyInt(), anyInt())).thenReturn(expected);
        tools.getPostComments("abc123", 5, 99);
        assertThat(capturedCommentsLimit()).isEqualTo(5);
        assertThat(capturedCommentsDepth()).isEqualTo(10);
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
