package com.prbot.webhook;

import com.prbot.config.GitHubProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Validates GitHub webhook payloads using HMAC-SHA256.
 *
 * Security design:
 *   1. Uses HmacSHA256 — GitHub signs every payload with the webhook secret
 *   2. Uses MessageDigest.isEqual() for CONSTANT-TIME comparison
 *      — String.equals() short-circuits on first mismatch, leaking timing info
 *      — An attacker can measure response time to brute-force the secret
 *      — isEqual() always compares all bytes regardless of where mismatch is
 *
 * Interview talking point: "I use MessageDigest.isEqual instead of .equals()
 * for the HMAC comparison. String equality short-circuits — that's a timing
 * side-channel. Constant-time comparison closes it. Same reason crypto
 * libraries never use == for secret comparison."
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SignatureValidator {

    private final GitHubProperties gitHubProperties;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    /**
     * @param payload   raw request body as received (before any parsing)
     * @param signature X-Hub-Signature-256 header value
     * @return true if signature matches, false otherwise
     */
    public boolean isValid(String payload, String signature) {
        if (payload == null || signature == null) return false;
        if (!signature.startsWith(SIGNATURE_PREFIX)) return false;

        String secret = gitHubProperties.getWebhook().getSecret();
        if (secret == null || secret.isBlank() || secret.equals("your-webhook-secret-here")) {
            log.error("GITHUB_WEBHOOK_SECRET is not configured — all webhooks will be rejected");
            return false;
        }

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM
            );
            mac.init(secretKey);

            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = SIGNATURE_PREFIX + HexFormat.of().formatHex(hash);

            // Timing-safe comparison — critical for HMAC validation
            return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Signature validation failed with exception", e);
            return false;
        }
    }
}
