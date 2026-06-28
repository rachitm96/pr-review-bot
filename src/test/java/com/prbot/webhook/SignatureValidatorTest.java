package com.prbot.webhook;

import com.prbot.config.GitHubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SignatureValidator")
class SignatureValidatorTest {

    private SignatureValidator validator;
    private GitHubProperties props;

    private static final String SECRET = "test-webhook-secret";
    private static final String PAYLOAD = "{\"action\":\"opened\",\"number\":42}";

    @BeforeEach
    void setUp() {
        props = new GitHubProperties();
        props.setToken("fake-token");
        GitHubProperties.Webhook webhook = new GitHubProperties.Webhook();
        webhook.setSecret(SECRET);
        props.setWebhook(webhook);

        validator = new SignatureValidator(props);
    }

    @Test
    @DisplayName("accepts valid HMAC-SHA256 signature")
    void validSignature() throws Exception {
        String sig = computeHmac(PAYLOAD, SECRET);
        assertThat(validator.isValid(PAYLOAD, "sha256=" + sig)).isTrue();
    }

    @Test
    @DisplayName("rejects tampered payload")
    void tamperedPayload() throws Exception {
        String sig = computeHmac(PAYLOAD, SECRET);
        String tamperedPayload = PAYLOAD.replace("opened", "closed");
        assertThat(validator.isValid(tamperedPayload, "sha256=" + sig)).isFalse();
    }

    @Test
    @DisplayName("rejects wrong secret")
    void wrongSecret() throws Exception {
        String sig = computeHmac(PAYLOAD, "wrong-secret");
        assertThat(validator.isValid(PAYLOAD, "sha256=" + sig)).isFalse();
    }

    @Test
    @DisplayName("rejects signature without sha256= prefix")
    void missingPrefix() throws Exception {
        String sig = computeHmac(PAYLOAD, SECRET);
        assertThat(validator.isValid(PAYLOAD, sig)).isFalse();
    }

    @Test
    @DisplayName("rejects null payload")
    void nullPayload() {
        assertThat(validator.isValid(null, "sha256=anything")).isFalse();
    }

    @Test
    @DisplayName("rejects null signature")
    void nullSignature() {
        assertThat(validator.isValid(PAYLOAD, null)).isFalse();
    }

    @Test
    @DisplayName("rejects unconfigured secret")
    void unconfiguredSecret() {
        GitHubProperties.Webhook webhook = new GitHubProperties.Webhook();
        webhook.setSecret("your-webhook-secret-here"); // default placeholder
        props.setWebhook(webhook);
        assertThat(validator.isValid(PAYLOAD, "sha256=anything")).isFalse();
    }

    // Helper: replicates what GitHub does when signing payloads
    private String computeHmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
