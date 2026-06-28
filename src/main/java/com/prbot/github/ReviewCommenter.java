package com.prbot.github;

import com.prbot.model.ReviewResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestReviewEvent;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Formats and posts AI review results back to GitHub.
 *
 * Posting strategy:
 *   1. One top-level PR review with summary table (APPROVE / REQUEST_CHANGES)
 *   2. Individual comments per issue for visibility in the PR timeline
 *
 * This matches how real code review works — a summary verdict plus
 * specific callouts that can be resolved individually.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewCommenter {

    private final GitHubClient gitHubClient;

    private static final Map<String, String> SEVERITY_EMOJI = Map.of(
        "CRITICAL", "🔴",
        "HIGH",     "🟠",
        "MEDIUM",   "🟡"
    );

    private static final Map<String, String> SEVERITY_LABEL = Map.of(
        "CRITICAL", "Critical — Fix before merge",
        "HIGH",     "High — Fix before merge",
        "MEDIUM",   "Medium — Recommended fix"
    );

    public void postReview(GHPullRequest pr, ReviewResult result) throws IOException {
        if (result.getReviews().isEmpty()) {
            postApproval(pr, result.getSummary());
            return;
        }

        // Determine review verdict
        boolean hasCriticalOrHigh = result.getReviews().stream()
            .anyMatch(r -> "CRITICAL".equals(r.getSeverity()) || "HIGH".equals(r.getSeverity()));

//        GHPullRequestReviewEvent event = hasCriticalOrHigh
//            ? GHPullRequestReviewEvent.REQUEST_CHANGES
//            : GHPullRequestReviewEvent.COMMENT;
        GHPullRequestReviewEvent event = GHPullRequestReviewEvent.COMMENT;

        // 1. Post the summary review
        String summaryBody = buildSummaryComment(result);
        gitHubClient.postReview(pr, event, summaryBody);

        // 2. Post individual issue comments for granular tracking
        for (ReviewResult.ReviewIssue issue : result.getReviews()) {
            try {
                gitHubClient.postComment(pr, formatIssueComment(issue));
            } catch (IOException e) {
                // Don't fail the whole review if one comment fails
                log.warn("Failed to post individual issue comment for {}: {}",
                    issue.getFilename(), e.getMessage());
            }
        }

        log.info("Posted review with {} issues (verdict={}) on PR #{}",
            result.getReviews().size(), event, pr.getNumber());
    }

    private void postApproval(GHPullRequest pr, String summary) throws IOException {
        String body = """
            ## ✅ AI Backend Review — No Issues Found

            %s

            ---
            *Reviewed by PR Review Bot powered by OpenAI GPT-4o*
            """.formatted(summary != null ? summary : "All changed files look good.");

        gitHubClient.postReview(pr, GHPullRequestReviewEvent.APPROVE, body);
        log.info("Posted approval on PR #{}", pr.getNumber());
    }

    private String buildSummaryComment(ReviewResult result) {
        List<ReviewResult.ReviewIssue> issues = result.getReviews();

        long critical = issues.stream().filter(r -> "CRITICAL".equals(r.getSeverity())).count();
        long high     = issues.stream().filter(r -> "HIGH".equals(r.getSeverity())).count();
        long medium   = issues.stream().filter(r -> "MEDIUM".equals(r.getSeverity())).count();

        String verdict = result.isApproved()
            ? "✅ **Approved** — Minor issues noted below"
            : "❌ **Changes Requested** — Critical/High issues must be resolved";

        // Build category breakdown
        String categoryList = issues.stream()
            .map(i -> "- %s `%s` in `%s`".formatted(
                SEVERITY_EMOJI.getOrDefault(i.getSeverity(), "⚪"),
                i.getCategory(),
                i.getFilename()))
            .reduce("", (a, b) -> a + "\n" + b);

        return """
            ## 🤖 AI Backend Review

            %s

            %s

            ### Issue Summary

            | Severity | Count |
            |----------|-------|
            | 🔴 Critical | %d |
            | 🟠 High     | %d |
            | 🟡 Medium   | %d |

            ### Issues Found
            %s

            ---
            *Reviewed by PR Review Bot · Powered by OpenAI GPT-4o*
            *Individual issue details posted as comments below ↓*
            """.formatted(verdict, result.getSummary(), critical, high, medium, categoryList);
    }

    private String formatIssueComment(ReviewResult.ReviewIssue issue) {
        String emoji = SEVERITY_EMOJI.getOrDefault(issue.getSeverity(), "⚪");
        String label = SEVERITY_LABEL.getOrDefault(issue.getSeverity(), issue.getSeverity());

        String frontendSection = (issue.getFrontendContext() != null && !issue.getFrontendContext().isBlank())
            ? "\n**Frontend context:** %s".formatted(issue.getFrontendContext())
            : "";

        return """
            %s **%s — %s** · `%s`

            **What's wrong:** %s

            **Production impact:** %s
            %s
            **Suggested fix:**
            ```
            %s
            ```

            > *Offending code: `%s`*
            """.formatted(
                emoji, label, issue.getCategory(), issue.getFilename(),
                issue.getIssue(),
                issue.getImpact(),
                frontendSection,
                issue.getFix() != null ? issue.getFix() : "See description above",
                issue.getLineHint() != null ? issue.getLineHint() : "see diff"
            );
    }
}
