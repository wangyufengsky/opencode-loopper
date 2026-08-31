package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerSemanticContracts.DECOMPOSITION_PLAN_PAYLOAD;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Adapts one legacy text response to candidate transport shape without applying policy rules. */
final class DesignerDecompositionLegacyCandidateAdapter {
    private final AiOutputExtractor extractor;
    private final ObjectMapper json;

    DesignerDecompositionLegacyCandidateAdapter(AiOutputExtractor extractor, ObjectMapper json) {
        this.extractor = extractor;
        this.json = json;
    }

    String candidateJson(String output) {
        try {
            JsonNode extracted = extractor.extractJson(output, DECOMPOSITION_PLAN_PAYLOAD,
                    "DECOMPOSER_LEGACY_CANDIDATE", JsonNode.class, value -> value, null).value();
            JsonNode adapted = adaptFinalEnvelope(extracted);
            return json.writeValueAsString(adapted);
        } catch (BadRequestException unextractable) {
            // The generic candidate compiler owns malformed/root diagnostics and attempt accounting.
            return output == null || output.isBlank() ? "null" : output;
        }
    }

    private JsonNode adaptFinalEnvelope(JsonNode root) {
        if (root == null || !root.isObject() || root.get("outcome") != null) return root;
        String status = text(root.get("status"));
        String outcome = switch (status == null ? "" : status.trim().toUpperCase()) {
            case "DIRECT_DESIGN", "DECOMPOSED" -> "READY";
            case "NEEDS_INPUT" -> "NEEDS_INPUT";
            case "MULTI_TASK_REQUIRED" -> "MULTI_TASK_REQUIRED";
            default -> null;
        };
        if (outcome == null) return root;

        ObjectNode candidate = json.createObjectNode();
        candidate.put("outcome", outcome);
        copy(candidate, "normalizedGoal", root.get("normalizedGoal"));
        ArrayNode constraints = candidate.putArray("globalConstraints");
        ArrayNode packages = candidate.putArray("workPackages");
        ArrayNode coverage = candidate.putArray("coverage");
        copyFinalConstraints(root.get("globalConstraints"), constraints, coverage);
        copyFinalPackages(root.get("workPackages"), packages, coverage);
        copyArray(candidate, "designGaps", root.get("designGaps"));
        copy(candidate, "reason", root.get("reason"));
        return candidate;
    }

    private void copyFinalConstraints(JsonNode source, ArrayNode target, ArrayNode coverage) {
        if (source == null || !source.isArray()) return;
        for (int index = 0; index < source.size(); index++) {
            JsonNode item = source.get(index);
            if (item != null && item.isObject()) {
                copy(target.addObject(), "text", item.get("text"));
                appendCoverage(item.get("requirementRefs"), "GLOBAL_CONSTRAINT", index, coverage);
            } else {
                target.add(item);
            }
        }
    }

    private void copyFinalPackages(JsonNode source, ArrayNode target, ArrayNode coverage) {
        if (source == null || !source.isArray()) return;
        for (int index = 0; index < source.size(); index++) {
            JsonNode item = source.get(index);
            if (item == null || !item.isObject()) {
                target.add(item);
                continue;
            }
            ObjectNode compact = target.addObject();
            for (String field : new String[]{"title", "objective", "scopeIn", "scopeOut",
                    "deliverables", "acceptanceIntent"}) {
                copy(compact, field, item.get(field));
            }
            copyArray(compact, "dependsOn", item.get("dependencies"));
            appendCoverage(item.get("requirementRefs"), "WORK_PACKAGE", index, coverage);
        }
    }

    private void appendCoverage(JsonNode refs, String targetType, int targetIndex, ArrayNode coverage) {
        if (refs == null || !refs.isArray()) return;
        for (JsonNode ref : refs) {
            ObjectNode mapping = coverage.addObject();
            copy(mapping, "requirementRef", ref);
            mapping.put("targetType", targetType);
            mapping.put("targetIndex", targetIndex);
        }
    }

    private void copyArray(ObjectNode target, String field, JsonNode value) {
        target.set(field, value != null && value.isArray() ? value : json.createArrayNode());
    }

    private void copy(ObjectNode target, String field, JsonNode value) {
        if (value == null) target.putNull(field);
        else target.set(field, value);
    }

    private String text(JsonNode value) {
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
