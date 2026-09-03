package io.opencode.loopper.runtime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** A reused Session's previous turns must never satisfy the current request. */
final class OpenCodeDesignMessageFilter {
    private OpenCodeDesignMessageFilter() { }
    static OpenCodeClient.SessionTranscript transcript(JsonNode messages, String messageId, OpenCodeResponseParser parser) {
        var current = parser.transcript(filter(messages, messageId));
        return new OpenCodeClient.SessionTranscript(current.parts(), parser.usage(messages));
    }
    static JsonNode interactions(JsonNode requests, JsonNode messages, String messageId) {
        if (messageId == null || requests == null || !requests.isArray()) return requests;
        var messageIds = new java.util.HashSet<String>();
        for (JsonNode message : messages) messageIds.add(message.path("info").path("id").asText());
        ArrayNode selected = JsonNodeFactory.instance.arrayNode();
        for (JsonNode request : requests) {
            String owner = request.path("tool").path("messageID").asText("");
            if (messageIds.contains(owner)) selected.add(request);
        }
        return selected;
    }
    static JsonNode filter(JsonNode messages, String messageId) {
        if (messageId == null || messages == null || !messages.isArray()) return messages;
        ArrayNode selected = JsonNodeFactory.instance.arrayNode();
        for (JsonNode message : messages) {
            JsonNode info = message.path("info");
            String parent = info.path("parentID").asText(info.path("parentId").asText(""));
            if (messageId.equals(info.path("id").asText()) || messageId.equals(parent)) selected.add(message);
        }
        return selected;
    }
}
