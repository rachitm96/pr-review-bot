package com.prbot.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prbot.ai.ReviewStrategyFactory;
import com.prbot.config.BotProperties;
import com.prbot.github.DiffParser;
import com.prbot.github.GitHubClient;
import com.prbot.github.ReviewCommenter;
import com.prbot.model.ParsedFile;
import com.prbot.model.ReviewRecord;
import com.prbot.model.ReviewResult;
import com.prbot.store.ReviewStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PullRequestHandler")
class PullRequestHandlerTest {

    @Mock private GitHubClient gitHubClient;
    @Mock private DiffParser diffParser;
    @Mock private ReviewStrategyFactory reviewStrategy;
    @Mock private ReviewCommenter commenter;
    @Mock private ReviewStore reviewStore;
    @Mock private GHPullRequest mockPR;

    private PullRequestHandler handler;
    private ObjectMapper objectMapper;
    private BotProperties botProps;

    private static final String OPENED_PAYLOAD = """
        {
          "action": "opened",
          "pull_request": {
            "number": 42,
            "title": "Add payment service",
            "body": "Implements payment processing",
            "html_url": "https://github.com/test/repo/pull/42",
            "user": {"login": "rachit"}
          },
          "repository": {
            "name": "test-repo",
            "owner": {"login": "test-owner"}
          }
        }
        """;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        botProps = new BotProperties();
        BotProperties.Review review = new BotProperties.Review();
        review.setTriggerOnSynchronize(true);
        review.setMinLinesThreshold(1);
        botProps.setReview(review);

        handler = new PullRequestHandler(
            gitHubClient, diffParser, reviewStrategy,
            commenter, reviewStore, objectMapper, botProps
        );
    }

    @Test
    @DisplayName("processes opened PR end-to-end and stores record")
    void processesOpenedPR() throws Exception {
        GHPullRequestFileDetail file = mock(GHPullRequestFileDetail.class);
        when(file.getAdditions()).thenReturn(10);

        when(gitHubClient.getPullRequest("test-owner", "test-repo", 42)).thenReturn(mockPR);
        when(gitHubClient.getPRFiles(mockPR)).thenReturn(List.of(file));

        ParsedFile parsedFile = ParsedFile.builder()
            .filename("PaymentService.java")
            .language("Java")
            .fileType(ParsedFile.FileType.BACKEND)
            .additions(10).deletions(0)
            .patch("+code").context("ctx")
            .build();

        when(diffParser.parse(anyList())).thenReturn(List.of(parsedFile));

        ReviewResult result = ReviewResult.builder()
            .reviews(List.of())
            .summary("LGTM")
            .approved(true)
            .stats(ReviewResult.ReviewStats.builder().build())
            .build();

        when(reviewStrategy.reviewFiles(anyList(), anyString(), anyString())).thenReturn(result);

        handler.handle(OPENED_PAYLOAD, "delivery-123");

        verify(commenter).postReview(eq(mockPR), any(ReviewResult.class));
        ArgumentCaptor<ReviewRecord> captor = ArgumentCaptor.forClass(ReviewRecord.class);
        verify(reviewStore).save(captor.capture());

        ReviewRecord saved = captor.getValue();
        assertThat(saved.getPrNumber()).isEqualTo(42);
        assertThat(saved.getPrTitle()).isEqualTo("Add payment service");
        assertThat(saved.getRepo()).isEqualTo("test-repo");
        assertThat(saved.isApproved()).isTrue();
    }

    @Test
    @DisplayName("skips PR when no reviewable files found")
    void skipsWhenNoReviewableFiles() throws Exception {
        GHPullRequestFileDetail file = mock(GHPullRequestFileDetail.class);
        when(file.getAdditions()).thenReturn(5);

        when(gitHubClient.getPullRequest(anyString(), anyString(), anyInt())).thenReturn(mockPR);
        when(gitHubClient.getPRFiles(mockPR)).thenReturn(List.of(file));
        when(diffParser.parse(anyList())).thenReturn(Collections.emptyList());

        handler.handle(OPENED_PAYLOAD, "delivery-456");

        verify(reviewStrategy, never()).reviewFiles(anyList(), anyString(), anyString());
        verify(commenter, never()).postReview(any(), any());
        verify(reviewStore, never()).save(any());
    }

    @Test
    @DisplayName("ignores closed PR action")
    void ignoresClosedAction() throws Exception {
        String closedPayload = OPENED_PAYLOAD.replace("\"opened\"", "\"closed\"");
        handler.handle(closedPayload, "delivery-789");
        verify(gitHubClient, never()).getPullRequest(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("skips PR with empty file list from GitHub")
    void skipsEmptyPR() throws Exception {
        when(gitHubClient.getPullRequest(anyString(), anyString(), anyInt())).thenReturn(mockPR);
        when(gitHubClient.getPRFiles(mockPR)).thenReturn(Collections.emptyList());

        handler.handle(OPENED_PAYLOAD, "delivery-000");

        verify(diffParser, never()).parse(anyList());
        verify(reviewStore, never()).save(any());
    }

    @Test
    @DisplayName("fails open on GitHub API exception — does not crash")
    void failsOpenOnException() throws Exception {
        when(gitHubClient.getPullRequest(anyString(), anyString(), anyInt()))
            .thenThrow(new RuntimeException("GitHub API down"));

        // Should not throw
        handler.handle(OPENED_PAYLOAD, "delivery-err");

        verify(reviewStore, never()).save(any());
    }
}
