package io.opencode.loopper.service;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Adds the submitted value at each exact JSON pointer without weakening role-specific validation. */
final class CandidateDiagnosticEnricher {
    private static final int MAX_VALUE_CHARS = 640;
    private static final int MAX_FIELD_CHARS = 1_000;

    private CandidateDiagnosticEnricher() { }

    static List<MachineCandidateSubmission.Problem> enrich(
            ObjectMapper json, String candidateJson, List<MachineCandidateSubmission.Problem> problems) {
        if (problems == null || problems.isEmpty()) return List.of();
        JsonNode candidate = parse(json, candidateJson);
        return problems.stream().map(problem -> enrich(problem, candidate)).toList();
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
        String type = value.isTextual() ? "string " : value.isArray() ? "array "
                : value.isObject() ? "object " : value.isNull() ? "null "
                : value.isBoolean() ? "boolean " : value.isNumber() ? "number " : "value ";
        return type + boundedValue(value.toString());
    }

    private static String candidatePointer(String pointer) {
        if (pointer == null || pointer.isBlank() || "/candidate".equals(pointer) || "/".equals(pointer)) return "";
        if (pointer.startsWith("/candidate/")) return pointer.substring("/candidate".length());
        return pointer;
    }

    private static String boundedValue(String value) {
        return value.length() <= MAX_VALUE_CHARS ? value : value.substring(0, MAX_VALUE_CHARS) + "…";
    }

    private static String bounded(String value) {
        return value.length() <= MAX_FIELD_CHARS ? value : value.substring(0, MAX_FIELD_CHARS);
    }
}
