package com.prbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * PR Review Bot — Entry Point
 *
 * Architecture overview:
 *   GitHub PR event → WebhookController → PullRequestHandler
 *     → DiffParser (extract signal from noise)
 *     → OpenAiClient (GPT-4o review)          ← standard mode
 *     → AgentReviewer (multi-step reasoning)  ← agent mode (bot.review.agent-mode-enabled=true)
 *     → ReviewCommenter (post back to GitHub)
 *     → ReviewStore (in-memory audit log)
 *
 * Future frontend review scope:
 *   When bot.skip-extensions has .html/.css/.jsx removed,
 *   FrontendReviewPrompts will be activated automatically
 *   via ReviewStrategyFactory.
 */
@SpringBootApplication
@EnableAsync
@ConfigurationPropertiesScan
public class PrReviewBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrReviewBotApplication.class, args);
    }
}
