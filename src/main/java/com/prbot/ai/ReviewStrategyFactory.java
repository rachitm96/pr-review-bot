package com.prbot.ai;

import com.prbot.config.BotProperties;
import com.prbot.model.ParsedFile;
import com.prbot.model.ReviewResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Strategy router — decides HOW to review based on PR content and config.
 *
 * Current strategies:
 *   STANDARD  → single OpenAI call with backend prompt
 *   AGENT     → two-pass reasoning (bot.review.agent-mode-enabled=true)
 *   FRONTEND  → frontend prompt (when frontend files are not skipped)
 *
 * Future strategies (add here as capabilities grow):
 *   SECURITY  → specialised security-focused prompt for auth/crypto code
 *   SQL       → dedicated SQL query analysis
 *   COMBINED  → backend + frontend in separate calls, results merged
 *
 * This factory is the extension point — adding a new review strategy
 * means adding a case here and a prompt in ReviewPrompts.
 * Nothing else in the call chain changes.
 *
 * Interview talking point: "I used a strategy pattern here so adding
 * frontend review later doesn't touch the orchestration layer.
 * The handler just calls reviewFiles() — it doesn't know or care
 * which strategy was selected."
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewStrategyFactory {

    private final OpenAiClient openAiClient;
    private final BotProperties botProperties;

    public ReviewResult reviewFiles(
            List<ParsedFile> files,
            String prTitle,
            String prDescription) {

        boolean agentMode = botProperties.getReview().isAgentModeEnabled();

        boolean hasFrontend = files.stream()
            .anyMatch(f -> f.getFileType() == ParsedFile.FileType.FRONTEND);

        // Agent mode takes priority when enabled
        if (agentMode) {
            log.info("Using AGENT review strategy (two-pass reasoning)");
            return openAiClient.agentReview(files, prTitle, prDescription);
        }

        // Frontend-only PRs (e.g. design system changes) get frontend review
        // This activates automatically when you remove .html/.css/.jsx from skip-extensions
        boolean hasBackend = files.stream()
            .anyMatch(f -> f.getFileType() == ParsedFile.FileType.BACKEND
                        || f.getFileType() == ParsedFile.FileType.UNKNOWN);

        if (hasFrontend && !hasBackend) {
            log.info("Using FRONTEND review strategy");
            // Frontend review uses same client, prompt is selected inside OpenAiClient
            return openAiClient.review(files, prTitle, prDescription);
        }

        // Default: standard backend review
        log.info("Using STANDARD backend review strategy");
        return openAiClient.review(files, prTitle, prDescription);
    }
}
