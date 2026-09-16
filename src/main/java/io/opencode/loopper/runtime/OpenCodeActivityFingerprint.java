package io.opencode.loopper.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;

/** Fingerprints the fetched request transcript before display truncation; never persists its contents. */
final class OpenCodeActivityFingerprint {
    private OpenCodeActivityFingerprint() { }
    static String of(JsonNode messages) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (JsonNode message : messages) {
                digest.update(message.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
