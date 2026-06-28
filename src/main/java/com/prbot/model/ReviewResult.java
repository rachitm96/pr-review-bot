package com.prbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// ============================================================
// Review domain models
// ============================================================

/**
 * The result of an AI review — maps directly from OpenAI JSON response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewResult {

    @Builder.Default
    private List<ReviewIssue> reviews = new ArrayList<>();

    private String summary;
    private boolean approved;

    /** Category breakdown for the dashboard */
    private ReviewStats stats;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReviewIssue {
        private String severity;       // CRITICAL | HIGH | MEDIUM
        private String category;       // e.g. "N+1 Query", "Missing Idempotency"
        private String filename;
        @JsonProperty("line_hint")
        private String lineHint;       // the offending line for context
        private String issue;          // what is wrong
        private String impact;         // production consequence
        private String fix;            // concrete remediation
        /** FUTURE: for frontend issues — component, prop, accessibility concern */
        private String frontendContext;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ReviewStats {
        private int critical;
        private int high;
        private int medium;
        private int totalFiles;
        private int totalLinesReviewed;
    }
}
