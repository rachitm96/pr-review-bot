package com.prbot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prbot.config.OpenAiProperties;
import com.prbot.model.ParsedFile;
import com.prbot.model.ReviewResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OpenAiClient")
class OpenAiClientTest {

    @Mock
    private RestTemplate restTemplate;

    private OpenAiClient client;
    private OpenAiProperties props;

    @BeforeEach
    void setUp() {
        props = new OpenAiProperties();
        props.setKey("test-key");
        props.setUrl("https://api.openai.com/v1/chat/completions");
        props.setModel("gpt-4o");
        props.setMaxTokens(3000);
        props.setTemperature(0.2);
        props.setTimeoutSeconds(60);

        client = new OpenAiClient(props, restTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("parses valid OpenAI response into ReviewResult")
    void parsesValidResponse() {
        String openAiResponse = """
            {
              "choices": [{
                "message": {
                  "content": "{\\"reviews\\":[{\\"severity\\":\\"CRITICAL\\",\\"category\\":\\"N+1 Query\\",\\"filename\\":\\"OrderService.java\\",\\"line_hint\\":\\"itemRepo.findByOrderId(order.getId())\\",\\"issue\\":\\"DB query inside loop\\",\\"impact\\":\\"DB pool exhaustion\\",\\"fix\\":\\"Use batch fetch\\"}],\\"summary\\":\\"One critical issue found.\\",\\"approved\\":false}"
                }
              }]
            }
            """;

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(openAiResponse));

        List<ParsedFile> files = List.of(
            ParsedFile.builder()
                .filename("OrderService.java")
                .language("Java")
                .fileType(ParsedFile.FileType.BACKEND)
                .additions(10)
                .deletions(2)
                .patch("+code")
                .context("### File: `OrderService.java`\n```diff\n+code\n```")
                .build()
        );

        ReviewResult result = client.review(files, "Fix orders", "Batch fix");

        assertThat(result.getReviews()).hasSize(1);
        assertThat(result.getReviews().get(0).getSeverity()).isEqualTo("CRITICAL");
        assertThat(result.getReviews().get(0).getCategory()).isEqualTo("N+1 Query");
        assertThat(result.isApproved()).isFalse();
    }

    @Test
    @DisplayName("returns safe default on rate limit (429)")
    void handleRateLimit() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));

        ReviewResult result = client.review(List.of(), "Test PR", "");

        assertThat(result.isApproved()).isFalse();
        assertThat(result.getSummary()).contains("429");
        assertThat(result.getReviews()).isEmpty();
    }

    @Test
    @DisplayName("returns safe default on invalid API key (401)")
    void handleUnauthorized() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        ReviewResult result = client.review(List.of(), "Test PR", "");

        assertThat(result.isApproved()).isFalse();
        assertThat(result.getReviews()).isEmpty();
    }

    @Test
    @DisplayName("returns safe default on malformed JSON response")
    void handleMalformedJson() {
        String badResponse = """
            {"choices": [{"message": {"content": "not-json-at-all"}}]}
            """;

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(badResponse));

        ReviewResult result = client.review(List.of(), "Test PR", "");

        assertThat(result.getReviews()).isEmpty();
        assertThat(result.getSummary()).contains("Failed to parse");
    }

    @Test
    @DisplayName("returns approved result when no issues found")
    void handleNoIssues() {
        String openAiResponse = """
            {
              "choices": [{
                "message": {
                  "content": "{\\"reviews\\":[],\\"summary\\":\\"Looks good.\\",\\"approved\\":true}"
                }
              }]
            }
            """;

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(openAiResponse));

        ReviewResult result = client.review(List.of(), "Clean PR", "");

        assertThat(result.isApproved()).isTrue();
        assertThat(result.getReviews()).isEmpty();
    }
}
