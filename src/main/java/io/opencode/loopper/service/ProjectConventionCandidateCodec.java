package io.opencode.loopper.service;

import io.opencode.loopper.service.ProjectConventionCompilation.Candidate;
import io.opencode.loopper.service.ProjectConventionCompilation.Problem;
import io.opencode.loopper.service.ProjectConventionCompilation.ProblemClass;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
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

/** Strict transport decoder for the closed PROJECT_CONVENTION_V1 candidate shape. */
final class ProjectConventionCandidateCodec {
    private static final int MAX_CANDIDATE_BYTES = 64 * 1024;
    private static final int MAX_EVIDENCE_BYTES = 1024 * 1024;
    private static final List<String> FIELD_ORDER =
            List.of("contractVersion", "componentKeys", "commandIds", "pathIds");
    private static final Set<String> FIELDS = Set.copyOf(FIELD_ORDER);
    private static final Set<String> AUTHORITY_FIELDS = Set.of(
            "runid", "taskid", "draftid", "projectconventiondraftid", "sourcerevision", "ownerversion",
            "submissionrevision", "state", "lifecycle", "permission", "permissions", "path", "paths",
            "allowedpaths", "forbiddenpaths", "command", "commands", "argv", "test", "tests",
            "evidence", "evidencecatalog", "sourcesha256", "hash", "sha256", "model", "fallback",
            "externalsessionid", "stableid");
    private static final Set<String> AUTHORITY_PREFIXES = Set.of(
            "runtime", "task", "draft", "source", "owner", "submission", "session", "attempt", "stage",
            "executioncycle", "permission", "allowedpath", "forbiddenpath", "command", "argv", "testtarget",
            "testcommand", "evidence", "hash", "sha", "fallback", "stable", "server");
    private final ObjectMapper json;
    private final ObjectMapper strictJson;

    ProjectConventionCandidateCodec(ObjectMapper json) {
        this.json = Objects.requireNonNull(json);
        this.strictJson = JsonMapper.builder(JsonFactory.builder()
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
            if (unknownFields.stream().anyMatch(ProjectConventionCandidateCodec::authorityField)) {
                return new Decoded(null, List.of(new Problem("PROJECT_CONVENTION_AUTHORITY_FIELD_FORBIDDEN",
                        "/candidate", "项目公约候选包含合同闭集之外的字段", List.of(), ProblemClass.SECURITY)));
            }
            if (!unknownFields.isEmpty()) {
                return new Decoded(null, List.of(new Problem("PROJECT_CONVENTION_FIELD_INVALID",
                        "/" + unknownFields.getFirst(), "项目公约候选只能包含合同闭集字段",
                        FIELD_ORDER, ProblemClass.MECHANICAL)));
            }
            if (FIELDS.stream().anyMatch(field -> !root.has(field))
                    || !root.path("contractVersion").isTextual()
                    || !stringArray(root.path("componentKeys"))
                    || !stringArray(root.path("commandIds"))
                    || !stringArray(root.path("pathIds"))) return invalid();
            if (root.path("componentKeys").size() > 64 || root.path("commandIds").size() > 64
                    || root.path("pathIds").size() > 128
                    || root.path("componentKeys").valueStream().anyMatch(item -> invalidText(item, 256))
                    || root.path("commandIds").valueStream().anyMatch(item -> invalidText(item, 256))
                    || root.path("pathIds").valueStream().anyMatch(item -> invalidText(item, 512))) {
                return new Decoded(null, List.of(new Problem("PROJECT_CONVENTION_CANDIDATE_SIZE_INVALID",
                        "/candidate", "项目公约候选字段数量或 UTF-8 长度超过闭集边界",
                        List.of(), ProblemClass.MECHANICAL)));
            }
            return new Decoded(json.treeToValue(root, Candidate.class), List.of());
        } catch (RuntimeException invalid) {
            return invalid();
        }
    }

    String canonical(Candidate candidate) {
        try { return json.writeValueAsString(candidate); }
        catch (RuntimeException impossible) { throw new IllegalStateException(impossible); }
    }

    Candidate requireCandidate(String value) {
        Decoded decoded = decode(value);
        if (!decoded.valid()) {
            Problem problem = decoded.problems().getFirst();
            throw new ConflictException(problem.code(), problem.staticDetail());
        }
        return decoded.candidate();
    }

    String canonicalEvidence(ProjectConventionCompilation.EvidenceCatalog source) {
        if (source == null) throw invalidEvidence();
        List<ProjectConventionCompilation.ComponentEvidence> components =
                new ArrayList<>(source.components());
        components.sort(Comparator.comparing(ProjectConventionCompilation.ComponentEvidence::relativeRoot,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ProjectConventionCompilation.ComponentEvidence::key,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        List<ProjectConventionCompilation.CommandEvidence> commands =
                new ArrayList<>(source.commands());
        commands.sort(Comparator.comparing(ProjectConventionCompilation.CommandEvidence::id,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ProjectConventionCompilation.CommandEvidence::componentKey,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        List<ProjectConventionCompilation.PathEvidence> paths = new ArrayList<>(source.paths());
        paths.sort(Comparator.comparing(ProjectConventionCompilation.PathEvidence::id,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ProjectConventionCompilation.PathEvidence::componentKey,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        ProjectConventionCompilation.EvidenceCatalog canonical =
                new ProjectConventionCompilation.EvidenceCatalog(
                        source.stackFingerprint(), components, commands, paths);
        try {
            String value = json.writeValueAsString(canonical);
            if (bytes(value) > MAX_EVIDENCE_BYTES) throw invalidEvidence();
            return value;
        } catch (ConflictException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw invalidEvidence();
        }
    }

    ProjectConventionCompilation.EvidenceCatalog requireEvidence(
            String value, String expectedSha256) {
        if (value == null || value.isBlank() || bytes(value) > MAX_EVIDENCE_BYTES
                || expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")
                || !sha256(value).equals(expectedSha256)) {
            throw invalidEvidence();
        }
        try {
            JsonNode root = strictJson.readTree(value);
            if (root == null || !root.isObject()) throw invalidEvidence();
            ProjectConventionCompilation.EvidenceCatalog evidence =
                    json.treeToValue(root, ProjectConventionCompilation.EvidenceCatalog.class);
            if (!value.equals(canonicalEvidence(evidence))) throw invalidEvidence();
            return evidence;
        } catch (ConflictException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw invalidEvidence();
        }
    }

    String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean stringArray(JsonNode value) {
        if (!value.isArray()) return false;
        for (JsonNode item : value) if (!item.isTextual()) return false;
        return true;
    }

    private static boolean invalidText(JsonNode item, int limit) {
        return item == null || !item.isTextual() || item.asText().isBlank() || bytes(item.asText()) > limit;
    }

    private static int bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean authorityField(String field) {
        if (field == null) return false;
        String normalized = field.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return AUTHORITY_FIELDS.contains(normalized)
                || AUTHORITY_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    private static Decoded invalid() {
        return new Decoded(null, List.of(new Problem("PROJECT_CONVENTION_CANDIDATE_JSON_INVALID",
                "/candidate", "项目公约候选必须是完整的 PROJECT_CONVENTION_V1 JSON 对象",
                List.of(), ProblemClass.MECHANICAL)));
    }

    private static ConflictException invalidEvidence() {
        return new ConflictException("PROJECT_CONVENTION_SOURCE_SNAPSHOT_INVALID",
                "Frozen project convention source snapshot is invalid");
    }

    record Decoded(Candidate candidate, List<Problem> problems) {
        boolean valid() { return problems.isEmpty(); }
    }
}
