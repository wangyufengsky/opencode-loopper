package io.opencode.loopper.api;

import io.opencode.loopper.config.LoopperProperties;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** In-memory startup token; the secret is never persisted or logged. */
@Component
class McpTokenProvider {
    private final String token;
    McpTokenProvider(LoopperProperties properties) {
        String configured = properties.getMcp().getBearerToken();
        if (configured != null && !configured.isBlank()) token = configured;
        else {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }
    boolean matches(String authorization) { return authorization != null && authorization.startsWith("Bearer ") && token.equals(authorization.substring(7)); }
}
