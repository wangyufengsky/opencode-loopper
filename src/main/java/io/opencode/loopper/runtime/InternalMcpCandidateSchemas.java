package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact JSON Schemas advertised by the seven role-specific private MCP tools. */
final class InternalMcpCandidateSchemas {
    private InternalMcpCandidateSchemas() { }

    static Map<String, Object> input(MachineCandidateKind kind) {
        return request(switch (kind) {
            case DECOMPOSITION_PLAN_V2 -> decomposition();
            case ACCEPTANCE_CLOSED_CHOICE_V7 -> acceptanceChoice();
            case PACKAGE_DESIGN_V1 -> packageDesign();
            case ROLLING_PACKAGE_PLAN_V1 -> rollingPackagePlan();
            case REVIEWER_REPORT_V1 -> reviewerReport();
            case PROJECT_CONVENTION_V1 -> projectConvention();
            case JUDGE_DECISION_V1 -> judgeDecision();
        });
    }

    static Map<String, Object> legacyInput() {
        return request(schema("object", Map.of(
                "description", "Legacy candidate payload for a frozen submit_candidate launch plan",
                "maxProperties", 64)));
    }

    private static Map<String, Object> request(Map<String, Object> candidate) {
        return object(List.of("runId", "idempotencyKey", "candidate", "expectedSubmissionRevision"), Map.of(
                "runId", string(1, null),
                "idempotencyKey", string(1, 128),
                "candidate", candidate,
                "expectedSubmissionRevision", integer(0)));
    }

    private static Map<String, Object> decomposition() {
        Map<String, Object> workPackage = object(
                List.of("title", "objective", "scopeIn", "scopeOut", "deliverables", "acceptanceIntent",
                        "dependsOn"),
                Map.of("title", string(1, null), "objective", string(1, null),
                        "scopeIn", stringArray(), "scopeOut", stringArray(), "deliverables", stringArray(),
                        "acceptanceIntent", stringArray(), "dependsOn", array(object(
                                List.of("packageIndex", "rationale"), Map.of(
                                        "packageIndex", integer(0), "rationale", string(1, null))))));
        return object(List.of("outcome", "normalizedGoal", "globalConstraints", "workPackages", "coverage",
                "designGaps", "reason"), Map.of(
                "outcome", enumeration("READY", "NEEDS_INPUT", "MULTI_TASK_REQUIRED"),
                "normalizedGoal", nullableString(),
                "globalConstraints", array(object(List.of("text"), Map.of("text", string(1, null)))),
                "workPackages", array(workPackage),
                "coverage", array(object(List.of("requirementRef", "targetType", "targetIndex"),
                        Map.of("requirementRef", string(1, null),
                                "targetType", enumeration("GLOBAL_CONSTRAINT", "WORK_PACKAGE"),
                                "targetIndex", integer(0), "rationale", string(0, null)))),
                "designGaps", array(object(List.of("code", "detail"), Map.of(
                        "code", string(1, null), "detail", string(1, null)))),
                "reason", nullableString()));
    }

    private static Map<String, Object> acceptanceChoice() {
        Map<String, Object> assignment = object(List.of("factIndex", "stageIndex"), Map.of(
                "factIndex", integer(0), "stageIndex", integer(0)));
        Map<String, Object> preference = object(List.of("factIndex", "capabilityIndexes"), Map.of(
                "factIndex", integer(0), "capabilityIndexes", integerArray()));
        return object(List.of("factAssignments", "capabilityPreferences"), Map.of(
                "factAssignments", array(assignment),
                "capabilityPreferences", array(preference),
                "summary", string(0, null),
                "handoffSummary", string(0, null)));
    }

    private static Map<String, Object> packageDesign() {
        Map<String, Object> requirement = object(List.of("key", "statement"), Map.of(
                "key", string(1, null), "statement", string(1, null)));
        Map<String, Object> scenario = object(
                List.of("key", "title", "precondition", "action", "observableResult", "invariant",
                        "requirementRefs"),
                Map.of("key", string(1, null), "title", string(1, null), "precondition", string(1, null),
                        "action", string(1, null), "observableResult", string(1, null),
                        "invariant", string(1, null), "requirementRefs", stringArray()));
        Map<String, Object> deliverable = object(
                List.of("key", "kind", "target", "description", "requirementRefs"),
                Map.of("key", string(1, null), "kind", enumeration("SCOPE", "DELIVERABLE"),
                        "target", string(1, null), "description", string(1, null),
                        "requirementRefs", stringArray()));
        Map<String, Object> review = object(
                List.of("key", "title", "criteria", "humanOnlyReason", "requirementRefs"),
                Map.of("key", string(1, null), "title", string(1, null), "criteria", string(1, null),
                        "humanOnlyReason", string(1, null), "requirementRefs", stringArray()));
        Map<String, Object> stage = object(
                List.of("key", "title", "objective", "includes", "dependencies"),
                Map.of("key", string(1, null), "title", string(1, null), "objective", string(1, null),
                        "includes", stringArray(), "dependencies", stringArray()));
        return object(List.of("contractVersion", "outcome", "requirements", "scenarios", "deliverables",
                "reviews", "stages", "gapCodes"), map(
                "contractVersion", constant("PACKAGE_DESIGN_V1"), "outcome", enumeration("READY", "NEEDS_INPUT"),
                "requirements", array(requirement), "scenarios", array(scenario),
                "deliverables", array(deliverable), "reviews", array(review), "stages", array(stage),
                "gapCodes", stringArray()));
    }

    private static Map<String, Object> rollingPackagePlan() {
        Map<String, Object> packageItem = object(
                List.of("packageKey", "title", "objective", "replaces", "dependencies", "requirementRefs"),
                Map.of("packageKey", string(1, null), "title", string(1, null),
                        "objective", string(1, null), "replaces", stringArray(),
                        "dependencies", stringArray(), "requirementRefs", stringArray()));
        return object(List.of("packages"), Map.of("packages", array(packageItem)));
    }

    private static Map<String, Object> reviewerReport() {
        Map<String, Object> finding = object(
                List.of("severity", "title", "detail", "path", "line", "recommendation"),
                Map.of("severity", enumeration("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"),
                        "title", string(1, null), "detail", string(1, null), "path", string(1, null),
                        "line", integer(1), "recommendation", string(1, null)));
        return object(List.of("title", "summary", "findings", "limitations"), Map.of(
                "title", string(1, null), "summary", string(1, null),
                "findings", array(finding), "limitations", stringArray()));
    }

    private static Map<String, Object> projectConvention() {
        return object(List.of("contractVersion", "componentKeys", "commandIds", "pathIds"), Map.of(
                "contractVersion", constant("PROJECT_CONVENTION_V1"),
                "componentKeys", stringArray(), "commandIds", stringArray(), "pathIds", stringArray()));
    }

    private static Map<String, Object> judgeDecision() {
        return object(List.of("contractVersion", "role", "verdict", "reason", "evidenceIds"), Map.of(
                "contractVersion", constant("JUDGE_DECISION_V1"), "role", string(1, null),
                "verdict", enumeration("PASS", "REVISE", "BLOCKED"),
                "reason", string(1, 4000), "evidenceIds", stringArray()));
    }

    private static Map<String, Object> object(List<String> required, Map<String, Object> properties) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("additionalProperties", false);
        result.put("required", required);
        result.put("properties", properties);
        return Map.copyOf(result);
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        return schema("array", Map.of("items", items));
    }

    private static Map<String, Object> stringArray() {
        return array(string(0, null));
    }

    private static Map<String, Object> integerArray() {
        return array(integer(0));
    }

    private static Map<String, Object> integer(int minimum) {
        return schema("integer", Map.of("minimum", minimum));
    }

    private static Map<String, Object> string(int minimumLength, Integer maximumLength) {
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("minLength", minimumLength);
        if (maximumLength != null) attributes.put("maxLength", maximumLength);
        return schema("string", attributes);
    }

    private static Map<String, Object> nullableString() {
        return Map.of("type", List.of("string", "null"));
    }

    private static Map<String, Object> constant(String value) {
        return Map.of("type", "string", "const", value);
    }

    private static Map<String, Object> enumeration(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> schema(String type, Map<String, Object> attributes) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.putAll(attributes);
        return Map.copyOf(result);
    }

    private static Map<String, Object> map(Object... entries) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return Map.copyOf(result);
    }
}
