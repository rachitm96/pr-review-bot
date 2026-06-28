package com.prbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "openai.api")
public class OpenAiProperties {
    private String key;
    private String url = "https://api.openai.com/v1/chat/completions";
    private String model = "gpt-4o";
    private int maxTokens = 3000;
    private double temperature = 0.2;
    private int timeoutSeconds = 60;
}
