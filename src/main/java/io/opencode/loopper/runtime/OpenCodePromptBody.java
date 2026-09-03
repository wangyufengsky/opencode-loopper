package io.opencode.loopper.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenCode wire format; attachment resources are issued separately from durable request identity. */
final class OpenCodePromptBody {
    private OpenCodePromptBody() { }

    static void restoreBusinessContext(Map<String, Object> body, OpenCodeClient.OpenCodeModel model) {
        body.putIfAbsent("agent", "build");
        if (model != null && model.providerId() != null && !model.providerId().isBlank()
                && model.modelId() != null && !model.modelId().isBlank()) {
            body.put("model", Map.of("providerID", model.providerId(), "modelID", model.modelId()));
        }
    }

    static Map<String, Object> encode(OpenCodeClient.PromptRequest prompt, OpenCodeClient.SessionProfile profile,
            boolean managed, OpenCodeClient.OpenCodeModel selectedModel, List<Map<String, Object>> files) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", prompt == null ? "" : prompt.text()));
        parts.addAll(files);
        body.put("parts", parts);
        if (prompt == null) return body;
        if (prompt.messageId() != null) body.put("messageID", prompt.messageId());
        if (prompt.system() != null && !prompt.system().isBlank()) body.put("system", prompt.system());
        String agent = OpenCodeAgentPolicy.promptAgent(prompt.agent(), profile, managed);
        if (agent != null) body.put("agent", agent);
        boolean structured = prompt.responseFormat() instanceof OpenCodeClient.ResponseFormat.JsonSchema;
        if (OpenCodeHttpClientSemantics.isDeepSeek(selectedModel) && (structured && Boolean.FALSE.equals(selectedModel.thinking())
                || managed && profile == OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS)) {
            body.put("variant", OpenCodeClient.STRUCTURED_NO_THINKING_VARIANT);
        }
        if (structured) {
            var format = (OpenCodeClient.ResponseFormat.JsonSchema) prompt.responseFormat();
            body.put("format", Map.of("type", "json_schema", "schema", format.schema(), "retryCount", format.retryCount()));
        }
        return body;
    }
}
