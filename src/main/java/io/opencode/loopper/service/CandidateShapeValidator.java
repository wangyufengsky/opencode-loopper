package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Recursively applies the advertised role schema and aggregates independent shape errors. */
final class CandidateShapeValidator {
    private static final int MAX_PROBLEMS = 64;
    private static final Set<String> SERVER_OWNED_FIELDS = Set.of(
            "allowedpaths", "branch", "command", "commands", "commit", "environment",
            "externalsessionid", "lifecycle", "owner", "ownerversion", "path", "permission",
            "permissions", "process", "runid", "runtimegenerationid", "servercommand", "sessionid",
            "sourcerevision", "stableid", "state", "status", "taskid", "testcommand", "verifiers",
            "workspacepath");

    private CandidateShapeValidator() { }

    static Result validate(ObjectMapper json, MachineCandidateKind kind, String candidateJson) {
        Collector collector = new Collector();
        JsonNode candidate;
        try {
            candidate = json.readTree(candidateJson);
        } catch (RuntimeException invalid) {
            collector.add(problem("CANDIDATE_JSON_INVALID", "/candidate",
                    MachineCandidateSubmission.ProblemCategory.SHAPE, "one complete JSON object",
                    "invalid JSON", "Submit one syntactically valid candidate object"));
            return collector.result();
        }
        Map<String, Object> requestSchema = InternalMcpContractCatalog.inputSchema(kind);
        Map<String, Object> schema = candidateSchema(requestSchema);
        if (candidate != null && candidate.isObject()) validateObject(candidate, schema, "", collector);
        else validateNode(candidate, schema, "/candidate", collector);
        return collector.result();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> candidateSchema(Map<String, Object> requestSchema) {
        Map<String, Object> properties = (Map<String, Object>) requestSchema.get("properties");
        return (Map<String, Object>) properties.get("candidate");
    }

    private static void validateNode(JsonNode node, Map<String, Object> schema,
            String pointer, Collector collector) {
        Object declaredType = schema.get("type");
        if (acceptsNull(declaredType) && (node == null || node.isNull())) return;
        String expectedType = primaryType(declaredType);
        if (!matches(node, expectedType)) {
            collector.add(problem("CANDIDATE_TYPE_INVALID", pointer,
                    MachineCandidateSubmission.ProblemCategory.TYPE, expectedType, type(node),
                    "Replace " + pointer + " with a JSON " + expectedType));
            return;
        }
        switch (expectedType) {
            case "object" -> validateObject(node, schema, pointer, collector);
            case "array" -> validateArray(node, schema, pointer, collector);
            case "string" -> validateString(node, schema, pointer, collector);
            case "integer" -> validateInteger(node, schema, pointer, collector);
            default -> { }
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateObject(JsonNode node, Map<String, Object> schema,
            String pointer, Collector collector) {
        Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> values
                ? (Map<String, Object>) values : Map.of();
        Set<String> required = schema.get("required") instanceof List<?> values
                ? new HashSet<>(values.stream().filter(String.class::isInstance).map(String.class::cast).toList())
                : Set.of();
        for (String name : required) {
            if (!node.has(name)) {
                String fieldPointer = child(pointer, name);
                collector.add(new MachineCandidateSubmission.Problem(
                        "CANDIDATE_FIELD_REQUIRED", fieldPointer,
                        "Required candidate field " + fieldPointer + " is missing", List.of(), "candidate",
                        MachineCandidateSubmission.ProblemCategory.SHAPE, "required field named " + name,
                        "missing", "Add " + fieldPointer + " with the declared type"));
            }
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            Object childSchema = properties.get(field.getKey());
            if (!(childSchema instanceof Map<?, ?> typed)) {
                String fieldPointer = child(pointer, field.getKey());
                boolean serverOwned = serverOwned(field.getKey());
                collector.add(new MachineCandidateSubmission.Problem(
                        "CANDIDATE_FIELD_UNKNOWN", fieldPointer,
                        "Candidate contains a field outside the role contract", List.copyOf(properties.keySet()),
                        "candidate", serverOwned ? MachineCandidateSubmission.ProblemCategory.AUTHORITY
                                : MachineCandidateSubmission.ProblemCategory.SHAPE,
                        "only fields listed in allowedValues", "undeclared field at " + fieldPointer,
                        serverOwned
                                ? "Remove " + fieldPointer + " and do not replace it with server-owned data"
                                : "Remove " + fieldPointer + " and resubmit the complete candidate object"));
                continue;
            }
            validateNode(field.getValue(), (Map<String, Object>) typed,
                    child(pointer, field.getKey()), collector);
        }
        Object maximum = schema.get("maxProperties");
        if (maximum instanceof Number number && node.size() > number.intValue()) {
            collector.add(problem("CANDIDATE_OBJECT_TOO_LARGE", pointer,
                    MachineCandidateSubmission.ProblemCategory.SHAPE,
                    "at most " + number.intValue() + " fields", node.size() + " fields",
                    "Remove undeclared or unnecessary fields from " + pointer));
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateArray(JsonNode node, Map<String, Object> schema,
            String pointer, Collector collector) {
        Object itemSchema = schema.get("items");
        if (!(itemSchema instanceof Map<?, ?> typed)) return;
        for (int index = 0; index < node.size(); index++) {
            validateNode(node.get(index), (Map<String, Object>) typed, child(pointer, Integer.toString(index)),
                    collector);
        }
    }

    private static void validateString(JsonNode node, Map<String, Object> schema,
            String pointer, Collector collector) {
        String value = node.textValue();
        int minimum = number(schema.get("minLength"), 0);
        Integer maximum = schema.get("maxLength") instanceof Number number ? number.intValue() : null;
        if (value.length() < minimum) {
            collector.add(problem("CANDIDATE_STRING_TOO_SHORT", pointer,
                    MachineCandidateSubmission.ProblemCategory.VALUE,
                    "string length of at least " + minimum, "shorter string",
                    "Provide a non-empty value at " + pointer));
        }
        if (maximum != null && value.length() > maximum) {
            collector.add(problem("CANDIDATE_STRING_TOO_LONG", pointer,
                    MachineCandidateSubmission.ProblemCategory.VALUE,
                    "string length no greater than " + maximum, "longer string",
                    "Shorten the value at " + pointer));
        }
        if (schema.containsKey("const") && !String.valueOf(schema.get("const")).equals(value)) {
            collector.add(new MachineCandidateSubmission.Problem(
                    "CANDIDATE_VALUE_NOT_ALLOWED", pointer, "Field must equal the declared contract constant",
                    List.of(String.valueOf(schema.get("const"))), "candidate",
                    MachineCandidateSubmission.ProblemCategory.VALUE, "one value from allowedValues",
                    "different string", "Replace " + pointer + " with the listed constant"));
        }
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value)) {
            List<String> values = allowed.stream().map(String::valueOf).toList();
            collector.add(new MachineCandidateSubmission.Problem(
                    "CANDIDATE_VALUE_NOT_ALLOWED", pointer, "Field is outside the declared closed set", values,
                    "candidate", MachineCandidateSubmission.ProblemCategory.VALUE,
                    "one value from allowedValues", "unlisted string",
                    "Replace " + pointer + " with one listed value"));
        }
    }

    private static void validateInteger(JsonNode node, Map<String, Object> schema,
            String pointer, Collector collector) {
        if (schema.get("minimum") instanceof Number minimum && node.longValue() < minimum.longValue()) {
            collector.add(problem("CANDIDATE_INTEGER_TOO_SMALL", pointer,
                    MachineCandidateSubmission.ProblemCategory.VALUE,
                    "integer greater than or equal to " + minimum.longValue(), "smaller integer",
                    "Increase the integer at " + pointer));
        }
    }

    private static MachineCandidateSubmission.Problem problem(String code, String pointer,
            MachineCandidateSubmission.ProblemCategory category, String expected, String actual, String repair) {
        return new MachineCandidateSubmission.Problem(code, pointer,
                pointer + " must be " + expected + "; received " + actual, List.of(), "candidate",
                category, expected, actual, repair);
    }

    private static boolean acceptsNull(Object type) {
        return type instanceof List<?> types && types.contains("null");
    }

    private static String primaryType(Object type) {
        if (type instanceof String value) return value;
        if (type instanceof List<?> values) {
            return values.stream().map(String::valueOf).filter(value -> !"null".equals(value))
                    .findFirst().orElse("null");
        }
        return "value";
    }

    private static boolean matches(JsonNode node, String type) {
        if (node == null || node.isNull()) return false;
        return switch (type) {
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "string" -> node.isTextual();
            case "integer" -> node.isIntegralNumber();
            case "boolean" -> node.isBoolean();
            default -> true;
        };
    }

    private static String type(JsonNode node) {
        if (node == null) return "missing";
        if (node.isNull()) return "null";
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isIntegralNumber()) return "integer";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        return "unsupported JSON value";
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean serverOwned(String fieldName) {
        return SERVER_OWNED_FIELDS.contains(fieldName.replace("_", "")
                .replace("-", "").toLowerCase(Locale.ROOT));
    }

    private static String child(String parent, String name) {
        return (parent == null ? "" : parent) + "/"
                + name.replace("~", "~0").replace("/", "~1");
    }

    record Result(List<MachineCandidateSubmission.Problem> problems, boolean complete) { }

    private static final class Collector {
        private final List<MachineCandidateSubmission.Problem> problems = new ArrayList<>();
        private boolean complete = true;

        void add(MachineCandidateSubmission.Problem problem) {
            if (problems.size() < MAX_PROBLEMS) problems.add(problem);
            else complete = false;
        }

        Result result() {
            return new Result(List.copyOf(problems), complete);
        }
    }
}
