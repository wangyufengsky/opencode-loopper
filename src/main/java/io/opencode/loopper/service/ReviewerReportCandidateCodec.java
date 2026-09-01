package io.opencode.loopper.service;

import io.opencode.loopper.service.ReviewerReportCompilation.Candidate;
import io.opencode.loopper.service.ReviewerReportCompilation.SourceFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict transport codec for Reviewer candidates and pre-I/O source-manifest metadata. */
final class ReviewerReportCandidateCodec {
    private static final int MAX_CANDIDATE_BYTES = 128 * 1024;
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final Set<String> CANDIDATE_FIELDS =
            Set.of("title", "summary", "findings", "limitations");
    private static final Set<String> FINDING_FIELDS =
            Set.of("severity", "title", "detail", "path", "line", "recommendation");

    private final ObjectMapper json;

    ReviewerReportCandidateCodec(ObjectMapper json) {
        this.json = json;
    }

    Decoded decodeCandidate(String value) {
        if (value == null || value.isBlank() || bytes(value) > MAX_CANDIDATE_BYTES) {
            return mechanical("REVIEWER_CANDIDATE_JSON_INVALID", "/candidate",
                    "Reviewer 候选必须是有界 JSON 对象");
        }
        try {
            JsonNode root = json.readTree(value);
            if (root == null || !root.isObject()) {
                return mechanical("REVIEWER_CANDIDATE_JSON_INVALID", "/candidate",
                        "Reviewer 候选必须是 JSON 对象");
            }
            if (unknown(root, CANDIDATE_FIELDS)) return authority("/candidate");
            JsonNode findings = root.get("findings");
            JsonNode limitations = root.get("limitations");
            if (!root.has("title") || !root.has("summary") || findings == null || !findings.isArray()
                    || limitations == null || !limitations.isArray()) {
                return mechanical("REVIEWER_CANDIDATE_JSON_INVALID", "/candidate",
                        "Reviewer 候选缺少固定合同字段");
            }
            for (int index = 0; index < findings.size(); index++) {
                JsonNode finding = findings.get(index);
                if (finding == null || !finding.isObject()) {
                    return mechanical("REVIEWER_CANDIDATE_JSON_INVALID", "/findings/" + index,
                            "Reviewer finding 必须是 JSON 对象");
                }
                if (unknown(finding, FINDING_FIELDS)) return authority("/findings/" + index);
            }
            Candidate candidate = json.treeToValue(root, Candidate.class);
            if (candidate == null || candidate.findings() == null || candidate.limitations() == null) {
                return mechanical("REVIEWER_CANDIDATE_JSON_INVALID", "/candidate",
                        "Reviewer 候选无法按固定合同读取");
            }
            return new Decoded(candidate, null, false);
        } catch (RuntimeException invalid) {
            return mechanical("REVIEWER_CANDIDATE_JSON_INVALID", "/candidate",
                    "Reviewer 候选无法按固定合同读取");
        }
    }

    Candidate requireCandidate(String value) {
        Decoded decoded = decodeCandidate(value);
        if (!decoded.valid()) {
            throw new ConflictException(decoded.problem().code(), decoded.problem().detail());
        }
        return decoded.candidate();
    }

    String canonicalSourceManifest(List<SourceFile> files) {
        if (files == null || files.size() > 100_000) throw invalidManifest();
        List<SourceFile> canonical = new ArrayList<>(files.size());
        Set<String> paths = new HashSet<>();
        for (SourceFile file : files) {
            if (file == null || !safePath(file.path()) || !paths.add(file.path())
                    || file.sizeBytes() < 0 || file.sizeBytes() > 16_000_000
                    || file.lineCount() < 1 || file.lineCount() > 1_000_000
                    || file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}")) {
                throw invalidManifest();
            }
            canonical.add(file);
        }
        canonical.sort(Comparator.comparing(SourceFile::path));
        String result = canonicalJson(canonical);
        if (bytes(result) > MAX_MANIFEST_BYTES) throw invalidManifest();
        return result;
    }

    List<SourceFile> requireSourceManifest(String value, String expectedSha256) {
        try {
            if (value == null || value.isBlank() || bytes(value) > MAX_MANIFEST_BYTES
                    || expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")
                    || !sha256(value).equals(expectedSha256)) {
                throw invalidManifest();
            }
            List<SourceFile> files = json.readValue(value, new TypeReference<List<SourceFile>>() { });
            if (!value.equals(canonicalSourceManifest(files))) throw invalidManifest();
            return List.copyOf(files);
        } catch (JacksonException invalid) {
            throw invalidManifest();
        }
    }

    String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Decoded authority(String pointer) {
        return new Decoded(null, new MachineCandidateSubmission.Problem(
                "REVIEWER_AUTHORITY_FIELD_FORBIDDEN", pointer,
                "Reviewer 候选包含服务端拥有的权限、状态或身份字段", List.of()), true);
    }

    private static Decoded mechanical(String code, String pointer, String detail) {
        return new Decoded(null, new MachineCandidateSubmission.Problem(
                code, pointer, detail, List.of()), false);
    }

    private static boolean unknown(JsonNode object, Set<String> allowed) {
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            if (!allowed.contains(property.getKey())) return true;
        }
        return false;
    }

    String canonicalJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static boolean safePath(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.startsWith("./")
                || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0 || value.contains("//")
                || value.endsWith("/") || value.matches("^[A-Za-z]:.*")) return false;
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || ".git".equals(segment) || ".env".equals(segment)
                    || (segment.startsWith(".env.") && !".env.example".equals(segment))) return false;
        }
        return true;
    }

    private static ConflictException invalidManifest() {
        return new ConflictException("REVIEWER_SOURCE_SNAPSHOT_INVALID",
                "Frozen Reviewer source manifest is invalid");
    }

    private static int bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    record Decoded(Candidate candidate, MachineCandidateSubmission.Problem problem, boolean security) {
        boolean valid() { return candidate != null && problem == null; }
    }
}
