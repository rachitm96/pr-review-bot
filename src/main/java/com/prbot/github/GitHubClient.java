package com.prbot.github;

import com.prbot.config.GitHubProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Thin wrapper around the kohsuke GitHub API client.
 * Centralises all GitHub API calls so they can be mocked in tests.
 *
 * Interview talking point: "I wrap the third-party client in my own interface
 * so tests never need to hit the real GitHub API — I inject a mock and test
 * the business logic in isolation."
 */
@Component
@Slf4j
public class GitHubClient {

    private final GitHubProperties props;
    private GitHub github;

    public GitHubClient(GitHubProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() throws IOException {
        String token = props.getToken();
        if (token == null || token.isBlank() || token.equals("your-github-pat-here")) {
            log.error("GITHUB_TOKEN is not configured. Bot will not be able to post reviews.");
            // Don't fail startup — allow webhook validation to still work
            return;
        }
        this.github = new GitHubBuilder()
            .withOAuthToken(token)
            .build();
        log.info("GitHub client initialised successfully");
    }

    /**
     * Fetch a specific pull request.
     */
    public GHPullRequest getPullRequest(String owner, String repo, int number) throws IOException {
        assertInitialised();
        return github.getRepository(owner + "/" + repo).getPullRequest(number);
    }

    /**
     * List all files changed in a pull request.
     */
    public List<GHPullRequestFileDetail> getPRFiles(GHPullRequest pr) throws IOException {
        return pr.listFiles().toList();
    }

    /**
     * Post a review (approve, request changes, or comment) on a PR.
     */
    public void postReview(GHPullRequest pr,
                           GHPullRequestReviewEvent event,
                           String body) throws IOException {
        pr.createReview()
          .event(event)
          .body(body)
          .create();
        log.info("Posted review event={} on PR #{}", event, pr.getNumber());
    }

    /**
     * Post a plain comment on a PR (used for individual issue callouts).
     */
    public void postComment(GHPullRequest pr, String body) throws IOException {
        pr.comment(body);
    }

    private void assertInitialised() {
        if (github == null) {
            throw new IllegalStateException(
                "GitHub client not initialised. Check GITHUB_TOKEN environment variable."
            );
        }
    }
}
