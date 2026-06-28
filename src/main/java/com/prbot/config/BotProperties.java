package com.prbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "bot")
public class BotProperties {
    private Review review = new Review();
    private List<String> skipExtensions = List.of(".lock", ".png", ".jpg", ".svg", ".md");

    @Data
    public static class Review {
        private boolean triggerOnSynchronize = true;
        private int minLinesThreshold = 5;
        private boolean postApprovalComment = true;
        private boolean agentModeEnabled = false;
    }
}
