package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.SessionFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Enforces machine-role step and repeated-tool-call safety independently of HTTP transport. */
final class OpenCodeMachineResponseInspector {
    private final ObjectMapper json;
    private final OpenCodeResponseParser responses;

    OpenCodeMachineResponseInspector(ObjectMapper json, OpenCodeResponseParser responses) {
        this.json = json;
        this.responses = responses;
    }

    void inspect(JsonNode messages, boolean structuredPrompt, int stepLimit, Runnable onStructured,
                 Consumer<String> onStructuredUnsupported) {
        int latestUserIndex = latestUserIndex(messages);
        int assistantTurns = 0;
        int stepStarts = 0;
        String previousToolSignature = null;
        String previousToolName = null;
        int repeatedToolCalls = 0;
        int index = 0;
        for (JsonNode message : messages) {
            if (index++ <= latestUserIndex || !"assistant".equalsIgnoreCase(responses.role(message))) continue;
            assistantTurns++;
            JsonNode info = message.path("info");
            JsonNode structured = info.path("structured");
            if (structured.isObject() && !structured.isEmpty()) onStructured.run();
            inspectStructuredError(info, structuredPrompt, onStructuredUnsupported);
            JsonNode parts = message.path("parts");
            if (!parts.isArray()) continue;
            for (JsonNode part : parts) {
                if ("step-start".equalsIgnoreCase(part.path("type").asText())) stepStarts++;
                if ("tool".equalsIgnoreCase(part.path("type").asText())) {
                    String toolName = normalizedToolName(part.path("tool").asText());
                    String signature = toolCallSignature(toolName, part);
                    repeatedToolCalls = signature.equals(previousToolSignature) ? repeatedToolCalls + 1 : 1;
                    previousToolSignature = signature;
                    previousToolName = toolName;
                    if (repeatedToolCalls >= 3) {
                        throw new SessionFailure("OPENCODE_MACHINE_TOOL_LOOP",
                                "Detected 3 consecutive identical " + previousToolName
                                        + " tool calls (signature " + sha256(signature).substring(0, 12) + ")");
                    }
                }
                inspectStructuredTool(part, structuredPrompt, onStructuredUnsupported);
            }
        }
        inspectStepLimit(Math.max(assistantTurns, stepStarts), stepLimit, structuredPrompt, onStructuredUnsupported);
    }

    private int latestUserIndex(JsonNode messages) {
        int latest = -1;
        int index = 0;
        for (JsonNode message : messages) {
            if ("user".equalsIgnoreCase(responses.role(message))) latest = index;
            index++;
        }
        return latest;
    }

    private void inspectStructuredError(JsonNode info, boolean structuredPrompt,
                                        Consumer<String> onStructuredUnsupported) {
        if (!structuredPrompt || info.path("error").isMissingNode() || info.path("error").isNull()) return;
        String detail = responses.errorDetail(info.path("error"));
        String type = responses.firstText(info.path("error").path("name"),
                info.path("error").path("code"), info.path("error").path("type"));
        if (!structuredError(type, detail)) return;
        onStructuredUnsupported.accept(detail);
        throw new SessionFailure("OPENCODE_STRUCTURED_OUTPUT_FAILED", detail);
    }

    private void inspectStructuredTool(JsonNode part, boolean structuredPrompt,
                                       Consumer<String> onStructuredUnsupported) {
        if (!structuredPrompt || !"tool".equalsIgnoreCase(part.path("type").asText())
                || !structuredTool(part.path("tool").asText())) return;
        JsonNode state = part.path("state");
        String status = state.path("status").asText("");
        if (!Set.of("error", "failed", "rejected").contains(status.toLowerCase(Locale.ROOT))) return;
        String detail = responses.firstText(state.path("error").path("message"), state.path("error"),
                state.path("message"), state.path("output"));
        if (detail.isBlank()) detail = "OpenCode structured-output tool failed";
        onStructuredUnsupported.accept(detail);
        throw new SessionFailure("OPENCODE_STRUCTURED_OUTPUT_FAILED", detail);
    }

    private void inspectStepLimit(int observedSteps, int stepLimit, boolean structuredPrompt,
                                  Consumer<String> onStructuredUnsupported) {
        if (stepLimit == 0 || observedSteps <= stepLimit) return;
        String detail = "OpenCode machine-response session exceeded Loopper's hard limit of "
                + stepLimit + " steps (observed " + observedSteps + ")";
        if (structuredPrompt) {
            onStructuredUnsupported.accept(detail);
            throw new SessionFailure("OPENCODE_STRUCTURED_OUTPUT_FAILED", detail);
        }
        throw new SessionFailure("OPENCODE_MACHINE_STEP_LIMIT_EXCEEDED", detail);
    }

    private boolean structuredTool(String tool) {
        return tool != null && "structuredoutput".equals(
                tool.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
    }

    private boolean structuredError(String type, String detail) {
        String value = ((type == null ? "" : type) + " " + (detail == null ? "" : detail))
                .toLowerCase(Locale.ROOT);
        return value.contains("structuredoutput") || value.contains("structured_output")
                || value.contains("json schema") || value.contains("json_schema");
    }

    private String normalizedToolName(String tool) {
        String normalized = tool == null ? "" : tool.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private String toolCallSignature(String toolName, JsonNode part) {
        JsonNode state = part.path("state");
        JsonNode arguments = firstPresent(state.path("input"), state.path("arguments"),
                part.path("input"), part.path("arguments"));
        return toolName + ":" + canonicalJson(arguments);
    }

    private JsonNode firstPresent(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null && !candidate.isMissingNode() && !candidate.isNull()) return candidate;
        }
        return json.getNodeFactory().nullNode();
    }

    private String canonicalJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "null";
        if (node.isArray()) {
            StringBuilder result = new StringBuilder("[");
            for (JsonNode item : node) {
                if (result.length() > 1) result.append(',');
                result.append(canonicalJson(item));
            }
            return result.append(']').toString();
        }
        if (node.isObject()) {
            StringBuilder result = new StringBuilder("{");
            node.propertyStream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                if (result.length() > 1) result.append(',');
                try {
                    result.append(json.writeValueAsString(entry.getKey()));
                } catch (JacksonException impossible) {
                    throw new IllegalStateException(impossible);
                }
                result.append(':').append(canonicalJson(entry.getValue()));
            });
            return result.append('}').toString();
        }
        return node.toString();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
