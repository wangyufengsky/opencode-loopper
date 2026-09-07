package io.opencode.loopper.service;

import io.opencode.loopper.persistence.CandidateSubmissionAttemptRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Computes bounded repair feedback from hashes and stored diagnostics, never storing rejected candidate text. */
final class CandidateRepairProgress {
    private CandidateRepairProgress() { }

    static Progress analyze(ObjectMapper json, String candidateJson, String requestSha,
            List<MachineCandidateSubmission.Problem> problems, boolean complete,
            List<CandidateSubmissionAttemptRow> recent) {
        JsonNode candidate = read(json, candidateJson);
        String contentSha = candidate == null ? requestSha : hash(json.writeValueAsString(canonical(candidate)));
        List<Issue> issues = java.util.stream.IntStream.range(0, problems.size()).mapToObj(index -> {
            var problem = problems.get(index);
            String identity = stablePointer(candidate, problem.pointer());
            String id = hash(problem.code() + "\n" + identity + "\n" + problem.expected());
            return new Issue(id, index, layer(problem));
        }).toList();
        Set<String> current = new LinkedHashSet<>();
        issues.forEach(issue -> current.add(issue.id()));
        Set<String> previous = new LinkedHashSet<>();
        boolean comparable = false;
        boolean repeated = false;
        if (recent != null && !recent.isEmpty()) {
            JsonNode last = read(json, recent.getFirst().responseJson());
            if (last != null && last.path("repairProgress").isObject()) {
                comparable = complete && last.path("diagnosticsComplete").asBoolean(false);
                last.path("repairProgress").path("issues").forEach(issue -> previous.add(issue.path("id").asText()));
            }
            for (var attempt : recent) {
                JsonNode response = read(json, attempt.responseJson());
                if (response != null && complete && response.path("diagnosticsComplete").asBoolean(false)
                        && "REJECTED".equals(attempt.outcome())
                        && contentSha.equals(response.path("repairProgress").path("contentSha256").asText())) {
                    repeated = true;
                }
            }
        }
        return new Progress(contentSha, issues, comparable,
                comparable ? difference(previous, current) : List.of(),
                comparable ? current.stream().filter(previous::contains).toList() : List.copyOf(current),
                comparable ? difference(current, previous) : List.of(), repeated);
    }

    private static List<String> difference(Set<String> left, Set<String> right) {
        return left.stream().filter(value -> !right.contains(value)).toList();
    }

    private static String stablePointer(JsonNode candidate, String pointer) {
        if (candidate == null || pointer == null) return String.valueOf(pointer);
        String[] parts = pointer.split("/", -1);
        if (parts.length > 2 && parts[2].matches("[0-9]+")) {
            JsonNode item = candidate.at("/" + parts[1] + "/" + parts[2]);
            if (item.path("key").isString()) {
                String key = Normalizer.normalize(item.path("key").asText(), Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
                parts[2] = "@" + hash(key);
            }
        }
        return String.join("/", parts);
    }

    private static Object canonical(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, Object> values = new TreeMap<>();
            node.properties().forEach(entry -> values.put(entry.getKey(), canonical(entry.getValue())));
            return values;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(canonical(item)));
            return values;
        }
        return node;
    }

    private static String layer(MachineCandidateSubmission.Problem problem) {
        return switch (problem.category()) {
            case AUTHORITY, SECURITY -> "AUTHORITY";
            case SHAPE, TYPE, VALUE -> "SHAPE";
            default -> problem.code().contains("REF") || problem.code().contains("DEPENDENC")
                    ? "REFERENCE" : "SEMANTIC_OR_COMPILE";
        };
    }

    private static JsonNode read(ObjectMapper json, String value) {
        try { return json.readTree(value); }
        catch (RuntimeException invalid) { return null; }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    record Issue(String id, int problemIndex, String layer) { }
    record Progress(String contentSha256, List<Issue> issues, boolean comparisonComplete,
                    List<String> resolved, List<String> remaining, List<String> introduced,
                    boolean repeatedCandidate) { }
}
