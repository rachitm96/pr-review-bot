package com.prbot.handler;

import com.fasterxml.jackson.databind.JsonNode;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the full PR review pipeline.
 *
 * Pipeline:
 *   1. Parse webhook payload (extract owner, repo, PR number, title)
 *   2. Filter by action (opened, synchronize) — ignore closed/merged
 *   3. Fetch PR files from GitHub API
 *   4. Parse + clean diffs
 *   5. Route to correct review strategy
 *   6. Post review back to GitHub
 *   7. Store record for dashboard
 *
 * All errors are caught and logged — a bad PR should never crash the server.
 * We fail open (don't block the PR) on review errors.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PullRequestHandler {

    private final GitHubClient gitHubClient;
    private final DiffParser diffParser;
    private final ReviewStrategyFactory reviewStrategy;
    private final ReviewCommenter commenter;
    private final ReviewStore reviewStore;
    private final ObjectMapper objectMapper;
    private final BotProperties botProperties;

    public void handle(String rawPayload, String deliveryId) {
        long startMs = System.currentTimeMillis();

        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            String action = payload.path("action").asText();

            // Only process opened and synchronize (new commits pushed) events
            if (!shouldProcess(action)) {
                log.debug("Skipping PR action={} delivery={}", action, deliveryId);
                return;
            }

            // Extract PR metadata from payload
            JsonNode prNode   = payload.path("pull_request");
            JsonNode repoNode = payload.path("repository");

            String owner    = repoNode.path("owner").path("login").asText();
            String repoName = repoNode.path("name").asText();
            int    prNumber = prNode.path("number").asInt();
            String prTitle  = prNode.path("title").asText();
            String prBody   = prNode.path("body").isNull() ? "" : prNode.path("body").asText();
            String prUrl    = prNode.path("html_url").asText();
            String author   = prNode.path("user").path("login").asText();

            log.info("Processing PR #{} '{}' by {} in {}/{} (action={})",
                prNumber, prTitle, author, owner, repoName, action);

            // 1. Fetch PR from GitHub API
            GHPullRequest pullRequest = gitHubClient.getPullRequest(owner, repoName, prNumber);
            List<GHPullRequestFileDetail> rawFiles = gitHubClient.getPRFiles(pullRequest);

            if (rawFiles.isEmpty()) {
                log.info("PR #{} has no changed files — skipping", prNumber);
                return;
            }

            int totalAdditions = rawFiles.stream()
                .mapToInt(GHPullRequestFileDetail::getAdditions).sum();

            // 2. Parse + filter diffs
            List<ParsedFile> parsedFiles = diffParser.parse(rawFiles);

            if (parsedFiles.isEmpty()) {
                log.info("PR #{} has no reviewable files after filtering (total files: {})",
                    prNumber, rawFiles.size());
                return;
            }

            log.info("Reviewing {} files ({} total additions) in PR #{}",
                parsedFiles.size(), totalAdditions, prNumber);

            // 3. AI Review
            ReviewResult result = reviewStrategy.reviewFiles(parsedFiles, prTitle, prBody);

            // 4. Post back to GitHub
            commenter.postReview(pullRequest, result);

            // 5. Store record for dashboard/audit
            long durationMs = System.currentTimeMillis() - startMs;
            ReviewResult.ReviewStats stats = result.getStats() != null
                ? result.getStats()
                : ReviewResult.ReviewStats.builder().build();

            ReviewRecord record = ReviewRecord.builder()
                .id(UUID.randomUUID().toString())
                .repo(repoName)
                .owner(owner)
                .prNumber(prNumber)
                .prTitle(prTitle)
                .prUrl(prUrl)
                .author(author)
                .totalFiles(parsedFiles.size())
                .totalAdditions(totalAdditions)
                .issueCount(result.getReviews() != null ? result.getReviews().size() : 0)
                .criticalCount(stats.getCritical())
                .highCount(stats.getHigh())
                .mediumCount(stats.getMedium())
                .approved(result.isApproved())
                .summary(result.getSummary())
                .reviewMode(botProperties.getReview().isAgentModeEnabled() ? "AGENT" : "STANDARD")
                .issues(result.getReviews())
                .timestamp(Instant.now())
                .durationMs(durationMs)
                .build();

            reviewStore.save(record);

            log.info("Review complete: PR #{} — {} issues, approved={}, took {}ms",
                prNumber, record.getIssueCount(), record.isApproved(), durationMs);

        } catch (Exception e) {
            log.error("Failed to handle PR event delivery={}", deliveryId, e);
            // Intentionally fail open — don't block the PR on a bot error
        }
    }

    private boolean shouldProcess(String action) {
        if ("opened".equals(action)) return true;
        if ("synchronize".equals(action) && botProperties.getReview().isTriggerOnSynchronize()) return true;
        return false;
    }
}
