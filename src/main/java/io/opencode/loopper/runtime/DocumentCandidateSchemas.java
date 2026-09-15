package io.opencode.loopper.runtime;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Version-one closed payloads; size limits match the frozen batch validator. */
final class DocumentCandidateSchemas {
    private DocumentCandidateSchemas() { }
    static Map<String, Object> requirements() {
        return object("requirements", array(object("key", text(32), "title", text(300), "group", text(300),
                "kind", enumeration("FUNCTION", "RULE", "PERMISSION", "EXCEPTION", "ACCEPTANCE", "CONSTRAINT"),
                "statement", text(12000), "sources", sources(), "acceptance", strings(32, 4000),
                "issues", strings(32, 2000)), 256), "coverage", coverage());
    }
    static Map<String, Object> requirementReview() {
        return object("approved", bool(), "reviewedRequirementKeys", strings(256, 32),
                "coverage", coverage(), "corrections", array(object(
                        "requirementKey", nullable(32),
                        "category", enumeration("OMISSION", "UNSUPPORTED", "WRONG_MERGE", "CONFLICT", "INTERPRETATION"),
                        "detail", text(4000), "sources", sources()), 256));
    }
    static Map<String, Object> assessment() {
        var item = object("requirementKey", text(32),
                "conclusion", enumeration("SATISFIED", "PARTIAL", "INCORRECT", "NOT_IMPLEMENTED", "UNDETERMINED"),
                "rationale", text(8000), "evidence", codeReferences(), "checkedPaths", strings(256, 1024),
                "missingEntryEvidence", nullable(4000), "testSourceCoverage", text(4000), "limitations", strings(32, 2000));
        var finding = object("key", text(64), "kind", enumeration("DEFECT", "VALIDATION_GAP", "SUGGESTION"),
                "severity", enumeration("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"),
                "title", text(300), "trigger", text(4000), "impact", text(4000),
                "recommendation", text(4000), "requirementKeys", strings(256, 32),
                "evidence", codeReferences(), "rootCauseKey", text(128));
        return object("snapshotSha", text(64), "items", array(item, 256), "findings", array(finding, 256),
                "limitations", strings(64, 2000));
    }
    static Map<String, Object> assessmentReview() {
        return object("snapshotSha", text(64), "approved", bool(),
                "reviewedRequirementKeys", strings(256, 32), "reviewedFindingKeys", strings(256, 64),
                "corrections", array(object("requirementKey", nullable(32), "findingKey", nullable(64),
                        "detail", text(4000)), 256));
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> directAssessment() {
        var old = (Map<String, Object>) assessment().get("properties");
        var item = (Map<String, Object>) ((Map<String, Object>) old.get("items")).get("items");
        var entry = object("title", text(300), "statement", text(12000), "sources", array(sourceRef(), 64),
                "issues", described(strings(32, 2000), "仅填写业务规则歧义或冲突；非空时 conclusion 必须为 UNDETERMINED。代码证据缺口写 assessment.limitations 或 VALIDATION_GAP。"), "assessment", item);
        return object("snapshotSha", described(nullable(64), "新交互合同填 null，由服务端绑定冻结代码快照；历史合同须填原始 SHA。显式错误 SHA 不会被覆盖。"), "entries", array(entry, 256), "findings", old.get("findings"),
                "skippedSections", array(object("source", sourceRef(), "reason", text(2000)), 2048),
                "limitations", strings(64, 2000));
    }
    static Map<String, Object> directReview() {
        var sourceOrNull = new LinkedHashMap<>(sourceRef()); sourceOrNull.put("type", List.of("object", "null"));
        return object("snapshotSha", described(nullable(64), "新交互合同填 null，由服务端绑定冻结代码快照；历史合同须填原始 SHA。显式错误 SHA 不会被覆盖。"), "approved", bool(), "reviewedRequirementKeys", strings(256, 32),
                "reviewedFindingKeys", strings(256, 64), "checkedSections", array(sourceRef(), 2048),
                "corrections", array(object("requirementKey", nullable(32), "findingKey", nullable(64),
                        "source", sourceOrNull, "detail", text(4000)), 256));
    }
    private static Map<String, Object> described(Map<String, Object> schema, String description) {
        var result = new LinkedHashMap<>(schema); result.put("description", description); return result;
    }
    private static Map<String, Object> sourceRef() { return object("fileId", text(64), "section", integer(0)); }
    private static Map<String, Object> codeReferences() {
        return array(object("path", text(1024), "blobSha", text(64), "startLine", integer(1),
                "endLine", integer(1), "quote", text(8000)), 64);
    }
    private static Map<String, Object> sources() {
        return array(object("fileId", text(64), "section", integer(0), "quote", text(4000)), 64);
    }
    private static Map<String, Object> coverage() {
        return array(object("fileId", text(64), "section", integer(0),
                "disposition", enumeration("REQUIREMENT", "BACKGROUND", "LIMITATION"),
                "requirementKeys", strings(256, 32), "reason", text(2000)), 2048);
    }
    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) properties.put((String) pairs[i], pairs[i + 1]);
        return Map.of("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
    private static Map<String, Object> text(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
    }
    private static Map<String, Object> nullable(int max) {
        return Map.of("type", List.of("string", "null"), "maxLength", max);
    }
    private static Map<String, Object> integer(int min) { return Map.of("type", "integer", "minimum", min); }
    private static Map<String, Object> bool() { return Map.of("type", "boolean"); }
    private static Map<String, Object> enumeration(String... values) {
        return Map.of("type", "string", "enum", Arrays.asList(values));
    }
    private static Map<String, Object> array(Map<String, Object> item, int max) {
        return Map.of("type", "array", "items", item, "maxItems", max);
    }
    private static Map<String, Object> strings(int max, int length) { return array(text(length), max); }
}
