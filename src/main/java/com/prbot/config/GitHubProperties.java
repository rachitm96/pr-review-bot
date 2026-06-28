package com.prbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Strongly-typed config binding for all application.yaml values.
 * Validated at startup — app won't start with missing required values.
 */
@Data
@Component
@ConfigurationProperties(prefix = "github")
public class GitHubProperties {
    private Webhook webhook = new Webhook();
    private String token;
    private int maxFilesPerPr = 20;
    private int maxAdditionsPerFile = 400;

    @Data
    public static class Webhook {
        private String secret;
    }
}
