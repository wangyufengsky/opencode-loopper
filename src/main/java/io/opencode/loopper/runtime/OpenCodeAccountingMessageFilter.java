package io.opencode.loopper.runtime;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Removes exact accounting user turns and their assistant children from business reads. */
final class OpenCodeAccountingMessageFilter {
    private static final String RESERVED_PREFIX = "msg_loopper_aicoding_";
    private static final ObjectMapper JSON = new ObjectMapper();
    private OpenCodeAccountingMessageFilter() { }

    static JsonNode filter(JsonNode messages, Set<String> persistedIds) {
        Set<String> excluded = new HashSet<>(persistedIds);
        for (JsonNode message : messages) {
            String id = id(message);
            if (id != null && id.startsWith(RESERVED_PREFIX)) excluded.add(id);
        }
        if (excluded.isEmpty()) return messages;
        var filtered = JSON.createArrayNode();
        for (JsonNode message : messages) {
            JsonNode info = message.path("info");
            String parent = info.path("parentID").asText(info.path("parentId").asText(null));
            if (!excluded.contains(id(message)) && !excluded.contains(parent)) filtered.add(message);
        }
        return filtered;
    }

    static OpenCodeClient.SessionStatus status(JsonNode raw, Set<String> identities,
                                               OpenCodeClient.SessionStatus runtimeStatus) {
        JsonNode business = filter(raw, identities);
        if (raw.isEmpty() || business.size() == raw.size()) return runtimeStatus;
        // The Session status has no parent message ID. Once a statistics turn is
        // last, only the business messages can establish business completion/error.
        JsonNode last = raw.get(raw.size() - 1);
        if (!business.isEmpty() && last.equals(business.get(business.size() - 1))) return runtimeStatus;
        OpenCodeClient.SessionStatus result = new OpenCodeResponseParser().messageStatus(business);
        return "UNKNOWN".equals(result.state()) ? new OpenCodeClient.SessionStatus("RUNNING") : result;
    }
    private static String id(JsonNode message) {
        return message.path("info").path("id").asText(message.path("id").asText(null));
    }
}
