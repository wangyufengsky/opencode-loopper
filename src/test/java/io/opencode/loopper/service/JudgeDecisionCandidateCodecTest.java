package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JudgeDecisionCandidateCodecTest {
    private final JudgeDecisionCandidateCodec codec = new JudgeDecisionCandidateCodec(new ObjectMapper());

    @Test
    void requiresExactCanonicalEvidenceBytesAndExpectedSha256() {
        String canonical = """
                {"items":[{"id":"test","kind":"VERIFIER","label":"Focused tests passed","sourceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}""";

        JudgeDecisionCompilation.EvidenceCatalog result = codec.requireEvidence(
                canonical, "0f4747c51cd0526e2cdc6b11cf00d674fa33ae3210ce19a087965f3560dee17f");

        assertThat(result.items()).containsExactly(new JudgeDecisionCompilation.EvidenceItem(
                "test", "VERIFIER", "Focused tests passed", "a".repeat(64)));
        assertInvalidEvidence(canonical, "b".repeat(64));
    }

    @Test
    void rejectsUnknownDuplicateTrailingOrSemanticallyInvalidEvidenceCatalog() {
        assertStructurallyInvalidEvidence("""
                {"items":[{"id":"test","kind":"VERIFIER","label":"ok","sourceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","path":"secret"}]}""");
        assertStructurallyInvalidEvidence("""
                {"items":[],"items":[]}""");
        assertStructurallyInvalidEvidence("""
                {"items":[]} {"items":[]}""");
        assertStructurallyInvalidEvidence("""
                {"items":[{"id":"test","kind":"VERIFIER","label":"bad\\u0000label","sourceSha256":"not-a-hash"}]}""");
        assertStructurallyInvalidEvidence("""
                { "items" : [ { "id" : "test", "kind" : "VERIFIER", "label" : "ok", "sourceSha256" : "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" } ] }""");
    }

    private void assertStructurallyInvalidEvidence(String value) {
        assertInvalidEvidence(value, sha256(value));
    }

    private void assertInvalidEvidence(String value, String hash) {
        assertThatThrownBy(() -> codec.requireEvidence(value, hash))
                .isInstanceOfSatisfying(ConflictException.class, failure -> assertThat(failure.code())
                        .isEqualTo("JUDGE_SOURCE_SNAPSHOT_INVALID"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
