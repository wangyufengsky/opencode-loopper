package io.opencode.loopper.api;

import io.opencode.loopper.service.MachineCandidateSubmission;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Aggregates independent private-MCP envelope errors before any candidate run or compiler is touched. */
final class InternalMcpRequestValidator {
    private static final Set<String> PARAMETERS = Set.of(
            "runId", "idempotencyKey", "candidate", "expectedSubmissionRevision");

    private InternalMcpRequestValidator() { }

    static Result validate(Map<String, Object> arguments) {
        Map<String, Object> values = arguments == null ? Map.of() : arguments;
        List<MachineCandidateSubmission.Problem> problems = new ArrayList<>();
        String runId = text(values, "runId", problems);
        String idempotencyKey = text(values, "idempotencyKey", problems);
        if (idempotencyKey != null && bytes(idempotencyKey) > 128) {
            problems.add(problem("CANDIDATE_PARAMETER_TOO_LONG", "/idempotencyKey", "idempotencyKey",
                    "string no greater than 128 UTF-8 bytes", bytes(idempotencyKey) + " UTF-8 bytes",
                    "Use a shorter stable idempotencyKey for this candidate submission"));
            idempotencyKey = null;
        }
        Object candidate = values.get("candidate");
        if (!(candidate instanceof Map<?, ?>)) {
            problems.add(problem("CANDIDATE_PARAMETER_TYPE_INVALID", "/candidate", "candidate",
                    "JSON object", type(candidate), "Pass candidate as an object, not encoded JSON text"));
        }
        Long revision = integer(values.get("expectedSubmissionRevision"));
        if (revision == null) {
            problems.add(problem("CANDIDATE_PARAMETER_TYPE_INVALID", "/expectedSubmissionRevision",
                    "expectedSubmissionRevision", "non-negative integer", type(values.get(
                            "expectedSubmissionRevision")), "Pass the returned submissionRevision as an integer"));
        } else if (revision < 0) {
            problems.add(problem("CANDIDATE_PARAMETER_VALUE_INVALID", "/expectedSubmissionRevision",
                    "expectedSubmissionRevision", "integer greater than or equal to 0", "negative integer",
                    "Use the latest non-negative submissionRevision returned by the tool"));
        }
        values.keySet().stream().filter(name -> !PARAMETERS.contains(name)).sorted().forEach(name ->
                problems.add(new MachineCandidateSubmission.Problem(
                        "CANDIDATE_PARAMETER_UNKNOWN", "/" + escape(name),
                        "The request contains a parameter outside the private tool contract",
                        PARAMETERS.stream().sorted().toList(), name,
                        MachineCandidateSubmission.ProblemCategory.AUTHORITY,
                        "only parameters listed in allowedValues", "undeclared request parameter",
                        "Remove /" + escape(name) + "; do not move server-owned data into candidate")));
        if (!problems.isEmpty()) return new Result(null, List.copyOf(problems));
        @SuppressWarnings("unchecked")
        Map<String, Object> candidateObject = (Map<String, Object>) candidate;
        return new Result(new Validated(runId, idempotencyKey, candidateObject, revision), List.of());
    }

    private static String text(Map<String, Object> values, String name,
            List<MachineCandidateSubmission.Problem> problems) {
        Object value = values.get(name);
        if (!(value instanceof String text)) {
            problems.add(problem("CANDIDATE_PARAMETER_TYPE_INVALID", "/" + name, name,
                    "non-empty string", type(value), "Pass " + name + " as a non-empty string"));
            return null;
        }
        if (text.isBlank()) {
            problems.add(problem("CANDIDATE_PARAMETER_VALUE_INVALID", "/" + name, name,
                    "non-empty string", "blank string", "Replace the blank " + name));
            return null;
        }
        return text;
    }

    private static Long integer(Object value) {
        if (value instanceof Byte number) return number.longValue();
        if (value instanceof Short number) return number.longValue();
        if (value instanceof Integer number) return number.longValue();
        if (value instanceof Long number) return number;
        if (value instanceof java.math.BigInteger number && number.bitLength() < 63) return number.longValue();
        return null;
    }

    private static MachineCandidateSubmission.Problem problem(
            String code, String pointer, String parameter, String expected, String actual, String repair) {
        return new MachineCandidateSubmission.Problem(code, pointer,
                parameter + " must be " + expected + "; received " + actual, List.of(), parameter,
                code.contains("TYPE") ? MachineCandidateSubmission.ProblemCategory.TYPE
                        : MachineCandidateSubmission.ProblemCategory.VALUE,
                expected, actual, repair);
    }

    private static String type(Object value) {
        if (value == null) return "missing";
        if (value instanceof String) return "string";
        if (value instanceof Map<?, ?>) return "object";
        if (value instanceof List<?>) return "array";
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        return "unsupported value type";
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    record Validated(String runId, String idempotencyKey, Map<String, Object> candidate,
                     long expectedSubmissionRevision) { }
    record Result(Validated value, List<MachineCandidateSubmission.Problem> problems) {
        boolean valid() { return value != null; }
    }
}
