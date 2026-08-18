package io.opencode.loopper.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Opaque cursor containing the deterministic sort value and id tie-breaker. */
public record PageCursor(String value, String id) {
    private static final String SEPARATOR = "\u0000";

    public String encode() {
        String payload = value + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public static PageCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = payload.indexOf(SEPARATOR);
            if (separator <= 0 || separator == payload.length() - 1 || payload.indexOf(SEPARATOR, separator + 1) >= 0) {
                throw invalid();
            }
            return new PageCursor(payload.substring(0, separator), payload.substring(separator + 1));
        } catch (IllegalArgumentException invalid) {
            throw invalid();
        }
    }

    public static int limit(Integer requested) {
        if (requested == null) return 50;
        if (requested < 1 || requested > 100) {
            throw new BadRequestException("PAGE_LIMIT_INVALID", "Page limit must be between 1 and 100");
        }
        return requested;
    }

    private static BadRequestException invalid() {
        return new BadRequestException("PAGE_CURSOR_INVALID", "Page cursor is invalid");
    }
}
