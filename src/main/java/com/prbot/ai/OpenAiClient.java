package com.prbot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prbot.config.OpenAiProperties;
import com.prbot.model.ParsedFile;
import com.prbot.model.ReviewResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Calls OpenAI GPT-4o to review code diffs.
 *
 * Why no official Java SDK?
 * At time of writing, the OpenAI Java SDK is in beta.
 * Using RestTemplate directly gives full control over the request/response
 * cycle and is easier to test — we can mock RestTemplate cleanly.
 *
 * Interview talking point: "I use RestTemplate directly rather than the
 * beta SDK because it's more testable — I can mock at the HTTP boundary —
 * and I understand the exact API contract. The versioning header, content
 * block structure, rate limit handling — none of that is hidden from me."
 *
 * Error handling strategy:
 *   - 429 Rate limit: log and return safe default (don't fail the PR)
 *   - 500 OpenAI error: log and return safe default
 *   - Timeout: log and return safe default
 *   - JSON parse failure: log raw response and return safe default
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiClient {

    private final OpenAiProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Review a set of parsed files from a PR.
     * Routes to backend or frontend prompt based on file types present.
     */
    public ReviewResult review(List<ParsedFile> files, String prTitle, String prDescription) {
        // Determine if this PR has any frontend files
        boolean hasFrontend = files.stream()
            .anyMatch(f -> f.getFileType() == ParsedFile.FileType.FRONTEND);
        boolean hasBackend = files.stream()
            .anyMatch(f -> f.getFileType() == ParsedFile.FileType.BACKEND
                        || f.getFileType() == ParsedFile.FileType.UNKNOWN);

        // FUTURE: when frontend review is enabled, merge results from both prompts
        // For now: use backend prompt (frontend files are filtered in BotProperties.skipExtensions)
        String systemPrompt = (hasFrontend && !hasBackend)
            ? ReviewPrompts.FRONTEND_SYSTEM_PROMPT
            : ReviewPrompts.BACKEND_SYSTEM_PROMPT;

        String userContent = buildUserMessage(files, prTitle, prDescription);
        log.debug("Sending {} files to OpenAI (model={})", files.size(), props.getModel());

        return callOpenAi(systemPrompt, userContent);
    }

    /**
     * Two-pass agent review — used when bot.review.agent-mode-enabled=true.
     *
     * WHY AN AGENT?
     * A single-pass review can miss cross-file interactions.
     * For example: a service has no idempotency key, AND the caller retries
     * without backoff. Each file looks independently mediocre, but together
     * they guarantee duplicate processing under failure.
     *
     * The agent first enumerates all suspicious patterns (broad),
     * then reasons about severity with full cross-file context (precise).
     *
     * Trade-off: 2x API calls, 2x latency (~30-60s vs ~15-30s).
     * Worth it for PRs that touch multiple critical services.
     *
     * Interview talking point: "I added an agent mode that does two passes —
     * one broad enumeration, one precise severity reasoning. The key insight
     * is that cross-file interactions are where the most serious bugs hide.
     * A single pass with a big context window sees everything but reasons
     * shallowly. Two focused passes reason more deeply."
     */
    public ReviewResult agentReview(List<ParsedFile> files, String prTitle, String prDescription) {
        String userContent = buildUserMessage(files, prTitle, prDescription);

        log.info("Agent mode: starting pass 1 (pattern enumeration)");
        ReviewResult pass1 = callOpenAi(ReviewPrompts.AGENT_PASS_1_PROMPT, userContent);

        // Build pass 2 prompt with pass 1 findings
        String pass2UserContent = """
            PASS 1 FINDINGS (candidate patterns to evaluate):
            %s
            
            ORIGINAL CODE:
            %s
            """.formatted(toJson(pass1), userContent);

        log.info("Agent mode: starting pass 2 (severity reasoning)");
        ReviewResult pass2 = callOpenAi(ReviewPrompts.AGENT_PASS_2_PROMPT, pass2UserContent);

        log.info("Agent review complete: {} issues found", pass2.getReviews().size());
        return pass2;
    }

    // ============================================================
    // Private helpers
    // ============================================================

    private ReviewResult callOpenAi(String systemPrompt, String userContent) {
        HttpHeaders headers = buildHeaders();
        Map<String, Object> body = buildRequestBody(systemPrompt, userContent);

        long startMs = System.currentTimeMillis();

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                props.getUrl(), request, String.class
            );

            long durationMs = System.currentTimeMillis() - startMs;
            log.info("OpenAI responded in {}ms", durationMs);

            return parseResponse(response.getBody());

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("OpenAI rate limit hit — returning safe default. Retry-After: {}",
                    e.getResponseHeaders() != null
                        ? e.getResponseHeaders().getFirst("Retry-After") : "unknown");
            } else if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.error("OpenAI API key is invalid or expired. Check OPENAI_API_KEY.");
            } else {
                log.error("OpenAI client error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            }
            return safeDefault("OpenAI API error: " + e.getStatusCode());

        } catch (HttpServerErrorException e) {
            log.error("OpenAI server error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return safeDefault("OpenAI service error — try again later");

        } catch (ResourceAccessException e) {
            log.error("OpenAI request timed out after {}s", props.getTimeoutSeconds(), e);
            return safeDefault("OpenAI request timed out — consider increasing openai.api.timeout-seconds");

        } catch (Exception e) {
            log.error("Unexpected error calling OpenAI", e);
            return safeDefault("Unexpected error: " + e.getMessage());
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(props.getKey());
        return headers;
    }

    private Map<String, Object> buildRequestBody(String systemPrompt, String userContent) {
        return Map.of(
            "model", props.getModel(),
            "max_tokens", props.getMaxTokens(),
            "temperature", props.getTemperature(),
            "response_format", Map.of("type", "json_object"),  // enforce JSON output
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userContent)
            )
        );
    }

    private String buildUserMessage(List<ParsedFile> files, String prTitle, String prDescription) {
        String fileContexts = files.stream()
            .map(ParsedFile::getContext)
            .collect(Collectors.joining("\n\n"));

        return """
            ## Pull Request: %s
            
            **Description:** %s
            
            ## Changed Files (%d files)
            
            %s
            """.formatted(
                prTitle,
                prDescription != null && !prDescription.isBlank() ? prDescription : "No description provided.",
                files.size(),
                fileContexts
            );
    }

    private ReviewResult parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // OpenAI response structure: choices[0].message.content
            String content = root
                .path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText();

            if (content == null || content.isBlank()) {
                log.error("OpenAI returned empty content. Full response: {}", responseBody);
                return safeDefault("Empty response from OpenAI");
            }

            ReviewResult result = objectMapper.readValue(content, ReviewResult.class);

            // Compute stats
            if (result.getReviews() != null) {
                long critical = result.getReviews().stream().filter(r -> "CRITICAL".equals(r.getSeverity())).count();
                long high     = result.getReviews().stream().filter(r -> "HIGH".equals(r.getSeverity())).count();
                long medium   = result.getReviews().stream().filter(r -> "MEDIUM".equals(r.getSeverity())).count();
                result.setStats(ReviewResult.ReviewStats.builder()
                    .critical((int) critical)
                    .high((int) high)
                    .medium((int) medium)
                    .build());
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to parse OpenAI response: {}", responseBody, e);
            return safeDefault("Failed to parse AI response");
        }
    }

    private ReviewResult safeDefault(String reason) {
        return ReviewResult.builder()
            .reviews(Collections.emptyList())
            .summary("Review could not be completed: " + reason)
            .approved(false)
            .build();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
