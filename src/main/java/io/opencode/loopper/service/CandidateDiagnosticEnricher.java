package io.opencode.loopper.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Adds the submitted value at each exact JSON pointer without weakening role-specific validation. */
final class CandidateDiagnosticEnricher {
    private static final int MAX_VALUE_BYTES = 768;
    private static final int MAX_FIELD_BYTES = 1_024;
    private static final int MAX_POINTER_BYTES = 256;
    private static final int MAX_PARAMETER_BYTES = 128;
    private static final int MAX_PROBLEMS = 64;
    private static final int MAX_ALLOWED_VALUES = 32;
    private static final int MAX_ALLOWED_VALUE_BYTES = 256;

    private CandidateDiagnosticEnricher() { }

    static List<MachineCandidateSubmission.Problem> enrich(
            ObjectMapper json, String candidateJson, List<MachineCandidateSubmission.Problem> problems) {
        if (problems == null || problems.isEmpty()) return List.of();
        JsonNode candidate = parse(json, candidateJson);
        return problems.stream().map(problem -> enrich(problem, candidate)).toList();
    }

    static BoundedProblems bound(List<MachineCandidateSubmission.Problem> problems) {
        if (problems == null || problems.isEmpty()) return new BoundedProblems(List.of(), true);
        List<MachineCandidateSubmission.Problem> bounded = new ArrayList<>();
        boolean complete = true;
        for (int index = 0; index < problems.size() && index < MAX_PROBLEMS; index++) {
            BoundedProblem result = bound(problems.get(index));
            bounded.add(result.problem());
            complete &= result.complete();
        }
        if (bounded.size() != problems.size()) complete = false;
        return new BoundedProblems(Collections.unmodifiableList(bounded), complete);
    }

    private static BoundedProblem bound(MachineCandidateSubmission.Problem problem) {
        if (problem == null) return new BoundedProblem(null, true);
        String pointer = utf8Bytes(problem.pointer()) <= MAX_POINTER_BYTES
                ? problem.pointer() : "/candidate";
        String parameter = utf8Bytes(problem.parameter()) <= MAX_PARAMETER_BYTES
                ? problem.parameter() : "candidate";
        String detail = bounded(problem.detail(), MAX_FIELD_BYTES);
        String expected = bounded(problem.expected(), MAX_FIELD_BYTES);
        String actual = bounded(problem.actual(), MAX_FIELD_BYTES);
        String repair = bounded(problem.repairHint(), MAX_FIELD_BYTES);
        List<String> allowed = problem.allowedValues().stream().limit(MAX_ALLOWED_VALUES)
                .map(value -> bounded(value, MAX_ALLOWED_VALUE_BYTES)).toList();
        boolean complete = pointer.equals(problem.pointer()) && parameter.equals(problem.parameter())
                && detail.equals(problem.detail()) && expected.equals(problem.expected())
                && actual.equals(problem.actual()) && repair.equals(problem.repairHint())
                && allowed.equals(problem.allowedValues());
        return new BoundedProblem(new MachineCandidateSubmission.Problem(
                problem.code(), pointer, detail, allowed, parameter, problem.category(),
                expected, actual, repair), complete);
    }

    private static MachineCandidateSubmission.Problem enrich(
            MachineCandidateSubmission.Problem problem, JsonNode candidate) {
        String submitted = submitted(candidate, problem.pointer());
        String actual = problem.actual();
        if (actual.contains("requires candidate-context rendering")
                || actual.matches("(?i)(different|unlisted|shorter|longer) string")
                || actual.equals("undeclared request parameter")) {
            actual = submitted;
        } else if (!actual.contains(submitted) && !"missing".equals(submitted)) {
            actual = bounded(actual + "; submitted value: " + submitted);
        }
        String expected = problem.expected().startsWith("value satisfying ")
                ? problem.detail() : problem.expected();
        String repair = problem.repairHint().startsWith("Replace candidate")
                ? "Correct candidate" + problem.pointer() + " so that: " + problem.detail()
                        + "; then resubmit the complete candidate"
                : problem.repairHint();
        return new MachineCandidateSubmission.Problem(
                problem.code(), problem.pointer(), problem.detail(), problem.allowedValues(),
                problem.parameter(), problem.category(), bounded(expected), bounded(actual), bounded(repair));
    }

    private static JsonNode parse(ObjectMapper json, String value) {
        try { return json.readTree(value); }
        catch (RuntimeException invalid) { return null; }
    }

    private static String submitted(JsonNode root, String pointer) {
        if (root == null) return "invalid JSON";
        String candidatePointer = candidatePointer(pointer);
        JsonNode value = candidatePointer.isEmpty() ? root : root.at(candidatePointer);
        if (value == null || value.isMissingNode()) return "missing";
        if (candidatePointer.isEmpty()) return rootSummary(value);
        String type = value.isTextual() ? "string " : value.isArray() ? "array "
                : value.isObject() ? "object " : value.isNull() ? "null "
                : value.isBoolean() ? "boolean " : value.isNumber() ? "number " : "value ";
        return type + boundedValue(value.toString());
    }

    private static String rootSummary(JsonNode value) {
        String serialized = value.toString();
        if (value.isObject()) {
            String fields = value.properties().stream().limit(16).map(Map.Entry::getKey)
                    .collect(Collectors.joining(", "));
            if (value.size() > 16) fields += ", …";
            return bounded("object candidate summary (UTF-8 bytes=" + utf8Bytes(serialized)
                    + ", top-level fields=[" + fields + "])", MAX_FIELD_BYTES);
        }
        if (value.isArray()) {
            return "array candidate summary (UTF-8 bytes=" + utf8Bytes(serialized)
                    + ", items=" + value.size() + ")";
        }
        return type(value) + boundedValue(serialized);
    }

    private static String type(JsonNode value) {
        if (value.isTextual()) return "string ";
        if (value.isNull()) return "null ";
        if (value.isBoolean()) return "boolean ";
        if (value.isNumber()) return "number ";
        return "value ";
    }

    private static String candidatePointer(String pointer) {
        if (pointer == null || pointer.isBlank() || "/candidate".equals(pointer) || "/".equals(pointer)) return "";
        if (pointer.startsWith("/candidate/")) return pointer.substring("/candidate".length());
        return pointer;
    }

    private static String boundedValue(String value) {
        return bounded(value, MAX_VALUE_BYTES);
    }

    private static String bounded(String value) {
        return bounded(value, MAX_FIELD_BYTES);
    }

    private static String bounded(String value, int maxBytes) {
        if (value == null || utf8Bytes(value) <= maxBytes) return value;
        byte[] suffix = "…".getBytes(StandardCharsets.UTF_8);
        int contentLimit = Math.max(0, maxBytes - suffix.length);
        int end = 0;
        int bytes = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > contentLimit) break;
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end) + "…";
    }

    private static int utf8Bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    record BoundedProblems(List<MachineCandidateSubmission.Problem> problems, boolean complete) { }
    private record BoundedProblem(MachineCandidateSubmission.Problem problem, boolean complete) { }
}
