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
            case DOCUMENT_REQUIREMENTS_V1 -> DocumentCandidateSchemas.requirements();
            case DOCUMENT_REQUIREMENT_REVIEW_V1 -> DocumentCandidateSchemas.requirementReview();
            case REQUIREMENT_CODE_ASSESSMENT_V1 -> DocumentCandidateSchemas.assessment();
            case REQUIREMENT_ASSESSMENT_REVIEW_V1 -> DocumentCandidateSchemas.assessmentReview();
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
                                        "packageIndex", described(integer(0), "Zero-based index of an earlier workPackages item; never a package ID"), "rationale", string(1, null))))));
        return object(List.of("outcome", "normalizedGoal", "globalConstraints", "workPackages", "coverage",
                "designGaps", "reason"), Map.of(
                "outcome", enumeration("READY", "NEEDS_INPUT", "MULTI_TASK_REQUIRED"),
                "normalizedGoal", nullableString(),
                "globalConstraints", array(object(List.of("text"), Map.of("text", string(1, null)))),
                "workPackages", boundedArray(workPackage, 6),
                "coverage", array(object(List.of("requirementRef", "targetType", "targetIndex"),
                        Map.of("requirementRef", string(1, null),
                                "targetType", enumeration("GLOBAL_CONSTRAINT", "WORK_PACKAGE"),
                                "targetIndex", described(integer(0), "Zero-based index into globalConstraints or workPackages selected by targetType"), "rationale", string(0, null)))),
                "designGaps", array(object(List.of("code", "detail"), Map.of(
                        "code", string(1, null), "detail", string(1, null)))),
                "reason", nullableString()));
    }

    private static Map<String, Object> acceptanceChoice() {
        Map<String, Object> assignment = object(List.of("factIndex", "stageIndex"), Map.of(
                "factIndex", described(integer(0), "Exact zero-based fact index from the frozen resolution; never invent an index"), "stageIndex", integer(0)));
        Map<String, Object> preference = object(List.of("factIndex", "capabilityIndexes"), Map.of(
                "factIndex", described(integer(0), "Exact zero-based fact index from the frozen resolution; never invent an index"), "capabilityIndexes", described(integerArray(), "Array of frozen capability indexes belonging to one complete allowed optimum; never a scalar")));
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
                        "includes", described(stringArray(), "Candidate-local keys from scenarios, reviews or deliverables. "
                                + "Include at least one scenario/review key and the deliverable keys owned by this stage. "
                                + "Do not put titles, paths or requirement keys here. Each scenario/review belongs to exactly one stage."),
                        "dependencies", described(stringArray(), "Candidate-local keys of earlier stages only; [] for no dependencies.")));
        return object(List.of("contractVersion", "outcome", "requirements", "scenarios", "deliverables",
                "reviews", "stages", "gapCodes"), map(
                "contractVersion", constant("PACKAGE_DESIGN_V1"), "outcome", enumeration("READY", "NEEDS_INPUT"),
                "requirements", boundedArray(requirement, io.opencode.loopper.domain.PackageDesignLimits.MAX_FACTS),
                "scenarios", boundedArray(scenario, io.opencode.loopper.domain.PackageDesignLimits.MAX_SCENARIOS),
                "deliverables", boundedArray(deliverable, io.opencode.loopper.domain.PackageDesignLimits.MAX_FACTS),
                "reviews", boundedArray(review, io.opencode.loopper.domain.PackageDesignLimits.MAX_FACTS),
                "stages", boundedArray(stage, io.opencode.loopper.domain.PackageDesignLimits.MAX_STAGES),
                "gapCodes", stringArray()));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> packageDesignV2Input() {
        var properties = new LinkedHashMap<>((Map<String, Object>) packageDesign().get("properties"));
        properties.put("contractVersion", constant("PACKAGE_DESIGN_V2"));
        properties.put("sourceBindings", boundedArray(object(List.of("key", "candidateRefs", "sourceRefs"), Map.of(
                "key", string(1, 128), "candidateRefs", boundedArray(string(1, 128), 128),
                "sourceRefs", boundedArray(string(1, 128), 128))), 128));
        properties.put("relations", boundedArray(object(List.of("key", "operator", "operands", "sourceRefs"), Map.of(
                "key", string(1, 128), "operator", enumeration("all", "any", "unless"),
                "operands", described(boundedArray(string(1, 128), 32),
                        "2..32 different scenario/relation keys; unless has exactly base and exception. All applicable any branches require behavior and coverage. Max depth 4; no cycles."),
                "sourceRefs", boundedArray(string(1, 128), 128))), 32));
        properties.put("gapClaims", boundedArray(object(List.of("key", "code", "sourceRefs", "question", "alternatives"), Map.of(
                "key", string(1, 128), "code", string(1, 128), "sourceRefs", boundedArray(string(1, 128), 128),
                "question", utf8String(2000, true), "alternatives", boundedArray(utf8String(1000, true), 4))), 16));
        return request(object(List.copyOf(properties.keySet()), properties));
    }

    private static Map<String, Object> rollingPackagePlan() {
        Map<String, Object> packageItem = object(
                List.of("packageKey", "title", "objective", "replaces", "dependencies", "requirementRefs"),
                Map.of("packageKey", string(1, null), "title", string(1, null),
                        "objective", string(1, null), "replaces", described(stringArray(), "Existing replaceable package keys from the frozen unfinished suffix"),
                        "dependencies", described(stringArray(), "Frozen retained package keys or earlier candidate packageKey values; no self or forward references"),
                        "requirementRefs", described(stringArray(), "Exact frozen requirement references; preserve required coverage")));
        return object(List.of("packages"), Map.of("packages", array(packageItem)));
    }

    private static Map<String, Object> reviewerReport() {
        Map<String, Object> finding = object(
                List.of("severity", "title", "detail", "path", "line", "recommendation"),
                Map.of("severity", enumeration("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"),
                        "title", utf8String(300, true), "detail", utf8String(4000, true), "path", described(utf8String(1024, true), "Managed relative source path observed in the frozen manifest; no absolute or protected path"),
                        "line", described(schema("integer", Map.of("minimum", 1, "maximum", 10000000)), "Exact one-based observed source line within that frozen file"), "recommendation", utf8String(4000, true)));
        return object(List.of("title", "summary", "findings", "limitations"), Map.of(
                "title", utf8String(200, true), "summary", utf8String(8000, true),
                "findings", described(boundedArray(finding, 128), "0..128 evidence-grounded findings; [] is valid when none is confirmed. Rendered report must fit 64 KiB"),
                "limitations", boundedArray(utf8String(2000, true), 32)));
    }

    private static Map<String, Object> projectConvention() {
        return object(List.of("contractVersion", "componentKeys", "commandIds", "pathIds"), Map.of(
                "contractVersion", constant("PROJECT_CONVENTION_V1"),
                "componentKeys", described(boundedArray(utf8String(256, false), 64), "Select at least one unique frozen component key"),
                "commandIds", described(boundedArray(utf8String(256, false), 64), "Unique frozen command IDs relevant to selected components; [] allowed, never raw commands"),
                "pathIds", described(boundedArray(utf8String(512, false), 128), "Unique frozen path IDs relevant to selected components; [] allowed, never raw paths")));
    }

    private static Map<String, Object> judgeDecision() {
        return object(List.of("contractVersion", "role", "verdict", "reason", "evidenceIds"), Map.of(
                "contractVersion", constant("JUDGE_DECISION_V1"), "role", described(enumeration("REQUIREMENT", "RISK"), "Must equal this run frozen owner role"),
                "verdict", enumeration("PASS", "REVISE", "BLOCKED"),
                "reason", described(utf8String(4000, true), "Nonblank single-line reason, at most 4000 UTF-8 bytes after stripping outer whitespace; no CR/LF/TAB or control characters. Preserve the evidence-grounded verdict"),
                "evidenceIds", described(stringArray(), "One or more unique exact IDs from the frozen evidence catalog")));
    }

    private static Map<String, Object> object(List<String> required, Map<String, Object> properties) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("additionalProperties", false);
        result.put("required", required);
        result.put("properties", properties);
        return Map.copyOf(result);
    }

    private static Map<String, Object> utf8String(int maximum, boolean strip) {
        return schema("string", Map.of("minLength", 1, "x-loopper-maxUtf8Bytes", maximum,
                "x-loopper-stripBeforeByteCount", strip,
                "description", "Nonblank text, maximum " + maximum + " UTF-8 bytes"));
    }

    private static Map<String, Object> boundedArray(Map<String, Object> items, int maximum) {
        return Map.of("type", "array", "items", items, "maxItems", maximum);
    }

    private static Map<String, Object> described(Map<String, Object> schema, String description) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(schema);
        result.put("description", schema.containsKey("description") ? schema.get("description") + ". " + description : description);
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
