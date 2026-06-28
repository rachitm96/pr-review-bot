package com.prbot.store;

import com.prbot.model.ReviewRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory store for completed PR reviews.
 *
 * No database required — this is a hackathon prototype.
 * In production: swap for a Postgres-backed repository with JPA.
 *
 * Thread-safety:
 *   ConcurrentLinkedDeque gives lock-free concurrent access.
 *   Multiple PRs can arrive simultaneously — ArrayList with synchronized
 *   blocks would serialize all access unnecessarily.
 *
 * Interview talking point: "I chose ConcurrentLinkedDeque because PRs
 * arrive concurrently and I need O(1) prepend for newest-first ordering.
 * ArrayDeque is faster but not thread-safe. ConcurrentLinkedDeque is
 * the right trade-off here — lock-free reads, atomic writes."
 */
@Component
@Slf4j
public class ReviewStore {

    @Value("${store.max-entries:100}")
    private int maxEntries;

    // Newest entries at the front — addFirst() + limit check
    private final ConcurrentLinkedDeque<ReviewRecord> store = new ConcurrentLinkedDeque<>();

    public void save(ReviewRecord record) {
        store.addFirst(record);
        // Trim to max size — remove oldest entries
        while (store.size() > maxEntries) {
            store.pollLast();
        }
        log.debug("Stored review record id={} (store size={})", record.getId(), store.size());
    }

    public List<ReviewRecord> getAll() {
        return new ArrayList<>(store);
    }

    public Optional<ReviewRecord> findById(String id) {
        return store.stream().filter(r -> r.getId().equals(id)).findFirst();
    }

    public List<ReviewRecord> findByRepo(String repo) {
        return store.stream().filter(r -> r.getRepo().equals(repo)).toList();
    }

    public int size() {
        return store.size();
    }
}


/**
 * REST API for the review store — consumed by the React dashboard.
 *
 * Endpoints:
 *   GET /api/reviews             — all reviews (newest first)
 *   GET /api/reviews/{id}        — single review with full issue list
 *   GET /api/reviews/repo/{repo} — reviews for a specific repo
 *   GET /api/stats               — aggregate stats for dashboard header
 */
@RestController
@RequestMapping("/api")
@Slf4j
class ReviewStoreController {

    private final ReviewStore store;

    ReviewStoreController(ReviewStore store) {
        this.store = store;
    }

    @GetMapping("/reviews")
    public List<ReviewRecord> getAllReviews() {
        return store.getAll();
    }

    @GetMapping("/reviews/{id}")
    public ReviewRecord getReview(@PathVariable String id) {
        return store.findById(id)
            .orElseThrow(() -> new ReviewNotFoundException("Review not found: " + id));
    }

    @GetMapping("/reviews/repo/{repo}")
    public List<ReviewRecord> getReviewsByRepo(@PathVariable String repo) {
        return store.findByRepo(repo);
    }

    @GetMapping("/stats")
    public DashboardStats getStats() {
        List<ReviewRecord> all = store.getAll();
        long totalIssues   = all.stream().mapToLong(ReviewRecord::getIssueCount).sum();
        long totalCritical = all.stream().mapToLong(ReviewRecord::getCriticalCount).sum();
        long approved      = all.stream().filter(ReviewRecord::isApproved).count();

        return new DashboardStats(
            all.size(),
            totalIssues,
            totalCritical,
            approved,
            all.size() - approved
        );
    }

    record DashboardStats(
        long totalReviews,
        long totalIssues,
        long totalCritical,
        long approved,
        long changesRequested
    ) {}
}


// Simple exception for 404 responses
class ReviewNotFoundException extends RuntimeException {
    ReviewNotFoundException(String message) { super(message); }
}
