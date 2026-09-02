package io.opencode.loopper.service;

import static io.opencode.loopper.service.JudgeDecisionCompilation.ProblemClass.MECHANICAL;
import static io.opencode.loopper.service.JudgeDecisionCompilation.ProblemClass.SECURITY;

import io.opencode.loopper.service.JudgeDecisionCompilation.Candidate;
import io.opencode.loopper.service.JudgeDecisionCompilation.EvidenceCatalog;
import io.opencode.loopper.service.JudgeDecisionCompilation.EvidenceItem;
import io.opencode.loopper.service.JudgeDecisionCompilation.Problem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Strict transport codec for the closed JUDGE_DECISION_V1 candidate object. */
final class JudgeDecisionCandidateCodec {
    private static final int MAX_CANDIDATE_BYTES = 64 * 1024;
    private static final int MAX_EVIDENCE_BYTES = 256 * 1024;
    private static final List<String> FIELD_ORDER =
            List.of("contractVersion", "role", "verdict", "reason", "evidenceIds");
    private static final Set<String> FIELDS = Set.copyOf(FIELD_ORDER);
    private static final Set<String> AUTHORITY_FIELDS = Set.of(
            "runid", "taskid", "batchid", "reviewbatchid", "judgerunid", "sourcerevision",
            "ownerversion", "submissionrevision", "state", "lifecycle", "permission", "permissions",
            "path", "paths", "allowedpaths", "forbiddenpaths", "command", "commands", "argv",
            "test", "tests", "evidence", "evidencecatalog", "sourcesha256", "hash", "sha256",
            "model", "fallback", "fallbackallowed", "externalsessionid", "generation", "stableid");
    private static final Set<String> AUTHORITY_PREFIXES = Set.of(
            "runtime", "task", "batch", "reviewbatch", "judge", "source", "owner", "submission",
            "session", "attempt", "stage", "executioncycle", "permission", "allowedpath",
            "forbiddenpath", "path", "command", "argv", "testtarget", "testcommand", "evidence",
            "hash", "sha", "fallback", "stable", "server");
    private static final Set<String> EVIDENCE_ROOT_FIELDS = Set.of("items");
    private static final Set<String> EVIDENCE_ITEM_FIELDS =
            Set.of("id", "kind", "label", "sourceSha256");
    private final ObjectMapper canonicalJson;
    private final ObjectMapper strictJson;

    JudgeDecisionCandidateCodec(ObjectMapper json) {
        canonicalJson = Objects.requireNonNull(json);
        strictJson = JsonMapper.builder(JsonFactory.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    Decoded decode(String value) {
        if (value == null || value.isBlank() || bytes(value) > MAX_CANDIDATE_BYTES) return invalid();
        try {
            JsonNode root = strictJson.readTree(value);
            if (root == null || !root.isObject()) return invalid();
            List<String> unknownFields = root.properties().stream().map(Map.Entry::getKey)
                    .filter(field -> !FIELDS.contains(field)).toList();
            if (unknownFields.stream().anyMatch(JudgeDecisionCandidateCodec::authorityField)) {
                return new Decoded(null, List.of(new Problem("JUDGE_DECISION_AUTHORITY_FIELD_FORBIDDEN",
                        "/candidate", "Judge 候选包含合同闭集之外的字段", List.of(), SECURITY)));
            }
            if (!unknownFields.isEmpty()) {
                return new Decoded(null, List.of(new Problem("JUDGE_DECISION_FIELD_INVALID",
                        "/candidate", "Judge 候选只能包含合同闭集字段", FIELD_ORDER, MECHANICAL)));
            }
            if (FIELDS.stream().anyMatch(field -> !root.has(field))
                    || !root.path("contractVersion").isTextual()
                    || !root.path("role").isTextual()
                    || !root.path("verdict").isTextual()
                    || !root.path("reason").isTextual()
                    || !stringArray(root.path("evidenceIds"))) return invalid();
            return new Decoded(canonicalJson.treeToValue(root, Candidate.class), List.of());
        } catch (RuntimeException invalid) {
            return invalid();
        }
    }

    String canonical(Object value) {
        try { return canonicalJson.writeValueAsString(value); }
        catch (RuntimeException impossible) { throw new IllegalStateException(impossible); }
    }

    EvidenceCatalog requireEvidence(String canonicalValue, String expectedSha256) {
        try {
            if (canonicalValue == null || canonicalValue.isBlank() || bytes(canonicalValue) > MAX_EVIDENCE_BYTES
                    || expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")
                    || !sha256(canonicalValue).equals(expectedSha256)) throw invalidEvidence();
            JsonNode root = strictJson.readTree(canonicalValue);
            if (root == null || !root.isObject() || !exactFields(root, EVIDENCE_ROOT_FIELDS)
                    || !root.path("items").isArray()) throw invalidEvidence();
            for (JsonNode item : root.path("items")) {
                if (item == null || !item.isObject() || !exactFields(item, EVIDENCE_ITEM_FIELDS)
                        || EVIDENCE_ITEM_FIELDS.stream().anyMatch(field -> !item.path(field).isTextual())) {
                    throw invalidEvidence();
                }
            }
            EvidenceCatalog catalog = canonicalJson.treeToValue(root, EvidenceCatalog.class);
            if (!validEvidenceCatalog(catalog) || !canonicalValue.equals(canonical(catalog))) {
                throw invalidEvidence();
            }
            return catalog;
        } catch (RuntimeException invalid) {
            throw invalidEvidence();
        }
    }

    boolean validEvidenceCatalog(EvidenceCatalog catalog) {
        if (catalog == null || catalog.items().isEmpty() || catalog.items().size() > 128) return false;
        Set<String> ids = new HashSet<>();
        for (EvidenceItem item : catalog.items()) {
            if (item == null || item.id() == null || !item.id().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
                    || !ids.add(item.id()) || item.kind() == null
                    || !item.kind().matches("[A-Z][A-Z0-9_]{0,63}")
                    || !safeLabel(item.label()) || item.sourceSha256() == null
                    || !item.sourceSha256().matches("[0-9a-f]{64}")) return false;
        }
        return true;
    }

    private static boolean stringArray(JsonNode node) {
        if (!node.isArray()) return false;
        for (JsonNode item : node) if (!item.isTextual()) return false;
        return true;
    }

    private static boolean exactFields(JsonNode object, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        object.properties().forEach(property -> actual.add(property.getKey()));
        return actual.equals(expected);
    }

    private static boolean authorityField(String field) {
        if (field == null) return false;
        String normalized = field.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return AUTHORITY_FIELDS.contains(normalized)
                || AUTHORITY_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    private static boolean safeLabel(String value) {
        return value != null && !value.isBlank() && bytes(value) <= 512
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static ConflictException invalidEvidence() {
        return new ConflictException("JUDGE_SOURCE_SNAPSHOT_INVALID",
                "Frozen Judge evidence catalog is invalid");
    }

    private static Decoded invalid() {
        return new Decoded(null, List.of(new Problem("JUDGE_DECISION_CANDIDATE_JSON_INVALID",
                "/candidate", "Judge 候选必须是完整的 JUDGE_DECISION_V1 JSON 对象",
                List.of(), MECHANICAL)));
    }

    private static int bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    record Decoded(Candidate candidate, List<Problem> problems) {
        boolean valid() { return candidate != null && problems.isEmpty(); }
    }
}
