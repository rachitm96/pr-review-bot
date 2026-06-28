package com.prbot.webhook;

import com.prbot.handler.PullRequestHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * Entry point for all GitHub webhook events.
 *
 * Security: Every request is HMAC-SHA256 validated before processing.
 *
 * Async design: Returns 202 immediately, processes async.
 * GitHub has a 10s webhook timeout and will retry on failure —
 * if we blocked on the OpenAI call (~15-30s), we'd get duplicate reviews.
 *
 * Interview talking point: "I return 202 before the AI call starts.
 * Holding the connection would cause GitHub to retry, creating duplicate
 * reviews. The async processing also means one slow PR can't block others."
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final SignatureValidator signatureValidator;
    private final PullRequestHandler pullRequestHandler;

    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handleWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestBody String rawBody) {

        log.debug("Received webhook event={} delivery={}", event, deliveryId);

        // Reject requests with no signature header
        if (signature == null || signature.isBlank()) {
            log.warn("Webhook rejected: missing X-Hub-Signature-256 header");
            return ResponseEntity.status(401).body("Missing signature");
        }

        // Validate HMAC before touching any payload content
        if (!signatureValidator.isValid(rawBody, signature)) {
            log.warn("Webhook rejected: invalid signature for delivery={}", deliveryId);
            return ResponseEntity.status(401).body("Invalid signature");
        }

        // Respond immediately — async processing below
        if ("pull_request".equals(event)) {
            CompletableFuture
                .runAsync(() -> pullRequestHandler.handle(rawBody, deliveryId))
                .exceptionally(ex -> {
                    log.error("Unhandled error in PR handler delivery={}", deliveryId, ex);
                    return null;
                });
        } else {
            log.debug("Ignoring non-PR event: {}", event);
        }

        return ResponseEntity.accepted().body("accepted");
    }

    /** Health check — useful for confirming ngrok tunnel is alive */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("PR Review Bot is running");
    }
}
