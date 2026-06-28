package com.prbot.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Persisted (in-memory) record of a completed PR review.
 * Exposed via /api/reviews for the dashboard.
 */
@Data
@Builder
public class ReviewRecord {
    private String id;
    private String repo;
    private String owner;
    private int prNumber;
    private String prTitle;
    private String prUrl;
    private String author;
    private int totalFiles;
    private int totalAdditions;
    private int issueCount;
    private int criticalCount;
    private int highCount;
    private int mediumCount;
    private boolean approved;
    private String summary;
    private String reviewMode;       // "STANDARD" or "AGENT"
    private List<ReviewResult.ReviewIssue> issues;
    private Instant timestamp;
    private long durationMs;         // how long the review took
}
