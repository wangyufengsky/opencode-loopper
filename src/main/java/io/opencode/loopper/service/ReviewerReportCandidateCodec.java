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
import java.util.Locale;
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
    private static final List<String> CANDIDATE_FIELD_ORDER =
            List.of("title", "summary", "findings", "limitations");
    private static final Set<String> CANDIDATE_FIELDS = Set.copyOf(CANDIDATE_FIELD_ORDER);
    private static final List<String> FINDING_FIELD_ORDER =
            List.of("severity", "title", "detail", "path", "line", "recommendation");
    private static final Set<String> FINDING_FIELDS = Set.copyOf(FINDING_FIELD_ORDER);
    private static final Set<String> AUTHORITY_FIELDS = Set.of(
            "runid", "taskid", "reportid", "analysisreportid", "sourcerevision", "ownerversion",
            "submissionrevision", "state", "lifecycle", "permission", "permissions", "allowedpaths",
            "forbiddenpaths", "command", "commands", "argv", "test", "tests", "evidencecatalog",
            "sourcesha256", "hash", "sha256", "model", "fallback", "externalsessionid", "stableid");
    private static final Set<String> AUTHORITY_PREFIXES = Set.of(
            "runtime", "task", "report", "analysisreport", "source", "owner", "submission", "session",
            "attempt", "stage", "executioncycle", "permission", "allowedpath", "forbiddenpath", "command",
            "argv", "testtarget", "testcommand", "evidence", "hash", "sha", "fallback", "stable", "server");

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
            List<String> unknown = unknown(root, CANDIDATE_FIELDS);
            if (unknown.stream().anyMatch(ReviewerReportCandidateCodec::authorityField)) {
                return authority("/candidate");
            }
            if (!unknown.isEmpty()) {
                return mechanical("REVIEWER_CANDIDATE_FIELD_INVALID", "/" + unknown.getFirst(),
                        "Reviewer 候选只能包含合同闭集字段", CANDIDATE_FIELD_ORDER);
            }
            JsonNode findings = root.get("findings");
            JsonNode limitations = root.get("limitations");
            for (String field : List.of("title", "summary")) {
                if (!root.path(field).isTextual()) {
                    return mechanical("REVIEWER_CANDIDATE_JSON_INVALID", "/" + field,
                            "该字段必填且必须为字符串，不要添加合同之外的字段");
                }
            }
            if (findings == null || !findings.isArray()) {
                return mechanical("REVIEWER_CANDIDATE_JSON_INVALID", "/findings",
                        "findings 必须为对象数组；确认无问题时使用 []");
            }
            if (limitations == null || !limitations.isArray()) {
                return mechanical("REVIEWER_CANDIDATE_JSON_INVALID", "/limitations",
                        "limitations 必须为字符串数组，例如 [\"未运行测试\"]；无限制时使用 []，不要添加其他字段");
            }
            for (int index = 0; index < limitations.size(); index++) {
                if (!limitations.get(index).isTextual()) {
                    return mechanical("REVIEWER_CANDIDATE_JSON_INVALID", "/limitations/" + index,
                            "limitations 的每个元素必须为字符串");
                }
            }
            for (int index = 0; index < findings.size(); index++) {
                JsonNode finding = findings.get(index);
                if (finding == null || !finding.isObject()) {
                    return mechanical("REVIEWER_CANDIDATE_JSON_INVALID", "/findings/" + index,
                            "Reviewer finding 必须是 JSON 对象");
                }
                List<String> findingUnknown = unknown(finding, FINDING_FIELDS);
                if (findingUnknown.stream().anyMatch(ReviewerReportCandidateCodec::authorityField)) {
                    return authority("/findings/" + index);
                }
                if (!findingUnknown.isEmpty()) {
                    return mechanical("REVIEWER_CANDIDATE_FIELD_INVALID",
                            "/findings/" + index + "/" + findingUnknown.getFirst(),
                            "Reviewer finding 只能包含合同闭集字段", FINDING_FIELD_ORDER);
                }
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
        return mechanical(code, pointer, detail, List.of());
    }

    private static Decoded mechanical(String code, String pointer, String detail, List<String> allowedValues) {
        return new Decoded(null, new MachineCandidateSubmission.Problem(
                code, pointer, detail, allowedValues), false);
    }

    private static List<String> unknown(JsonNode object, Set<String> allowed) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            if (!allowed.contains(property.getKey())) result.add(property.getKey());
        }
        return List.copyOf(result);
    }

    private static boolean authorityField(String field) {
        if (field == null) return false;
        String normalized = field.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return AUTHORITY_FIELDS.contains(normalized)
                || AUTHORITY_PREFIXES.stream().anyMatch(normalized::startsWith);
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
