package io.opencode.loopper.runtime;

import io.opencode.loopper.runtime.OpenCodeClient.AgentInfo;
import io.opencode.loopper.runtime.OpenCodeClient.PendingPermission;
import io.opencode.loopper.runtime.OpenCodeClient.PendingQuestion;
import io.opencode.loopper.runtime.OpenCodeClient.QuestionOption;
import io.opencode.loopper.runtime.OpenCodeClient.QuestionPrompt;
import io.opencode.loopper.runtime.OpenCodeClient.SessionMessageRef;
import io.opencode.loopper.runtime.OpenCodeClient.SessionPart;
import io.opencode.loopper.runtime.OpenCodeClient.SessionStatus;
import io.opencode.loopper.runtime.OpenCodeClient.SessionTranscript;
import io.opencode.loopper.runtime.OpenCodeClient.UsageRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Converts OpenCode wire JSON into bounded domain projections without performing HTTP calls. */
final class OpenCodeResponseParser {
    JsonNode listBody(JsonNode body) {
        JsonNode value = body != null && body.isArray() ? body : body == null ? null : body.path("data");
        return value != null && value.isArray() ? value : null;
    }

    JsonNode latestAssistantAfterUser(JsonNode messages) {
        int latestUserIndex = -1;
        JsonNode latest = null;
        int index = 0;
        for (JsonNode message : messages) {
            String role = role(message);
            if ("user".equalsIgnoreCase(role)) {
                latestUserIndex = index;
                latest = null;
            } else if ("assistant".equalsIgnoreCase(role) && index > latestUserIndex) {
                latest = message;
            }
            index++;
        }
        return latest;
    }

    String liveOutput(JsonNode messages) {
        JsonNode latest = latestAssistantAfterUser(messages);
        return latest == null ? "" : bounded(assistantText(latest));
    }

    SessionTranscript transcript(JsonNode messages) {
        List<SessionPart> result = new ArrayList<>();
        int messageIndex = 0;
        for (JsonNode message : messages) {
            if (!"assistant".equalsIgnoreCase(role(message))) {
                messageIndex++;
                continue;
            }
            JsonNode parts = message.path("parts");
            if (parts.isArray()) {
                int partIndex = 0;
                for (JsonNode part : parts) {
                    SessionPart parsed = monitorPart(part, message, messageIndex, partIndex++);
                    if (parsed != null && result.size() < 200) result.add(parsed);
                }
            } else if (message.hasNonNull("text")) {
                result.add(new SessionPart("message-" + messageIndex, "OUTPUT", "模型输出",
                        bounded(message.path("text").asText()), null,
                        startedAt(message.path("info").path("time").path("created"))));
            }
            messageIndex++;
        }
        return new SessionTranscript(result, usage(messages));
    }

    List<SessionMessageRef> messageRefs(JsonNode messages) {
        List<SessionMessageRef> result = new ArrayList<>();
        for (JsonNode message : messages) {
            JsonNode info = message.path("info");
            String id = firstText(info.path("id"), message.path("id"));
            if (id.isBlank()) continue;
            result.add(new SessionMessageRef(id, firstText(info.path("role"), message.path("role")),
                    startedAt(info.path("time").path("created")),
                    startedAt(info.path("time").path("completed"))));
        }
        return List.copyOf(result);
    }

    List<PendingQuestion> questions(JsonNode body, String expectedSessionId) {
        JsonNode requests = listBody(body);
        if (requests == null) return null;
        List<PendingQuestion> result = new ArrayList<>();
        for (JsonNode request : requests) {
            String sessionId = request.path("sessionID").asText("");
            if (!expectedSessionId.equals(sessionId)) continue;
            String requestId = request.path("id").asText("");
            if (requestId.isBlank()) continue;
            List<QuestionPrompt> questions = new ArrayList<>();
            JsonNode prompts = request.path("questions");
            if (prompts.isArray()) {
                for (JsonNode prompt : prompts) {
                    List<QuestionOption> options = new ArrayList<>();
                    JsonNode optionNodes = prompt.path("options");
                    if (optionNodes.isArray()) {
                        for (JsonNode option : optionNodes) {
                            options.add(new QuestionOption(option.path("label").asText(""),
                                    option.path("description").asText("")));
                        }
                    }
                    questions.add(new QuestionPrompt(prompt.path("question").asText(""),
                            prompt.path("header").asText(""), options,
                            prompt.path("multiple").asBoolean(false),
                            !prompt.has("custom") || prompt.path("custom").asBoolean(true)));
                }
            }
            result.add(new PendingQuestion(requestId, sessionId, questions));
        }
        return List.copyOf(result);
    }

    List<PendingPermission> permissions(JsonNode body, String expectedSessionId) {
        JsonNode requests = listBody(body);
        if (requests == null) return null;
        List<PendingPermission> result = new ArrayList<>();
        for (JsonNode request : requests) {
            String sessionId = request.path("sessionID").asText("");
            if (!expectedSessionId.equals(sessionId)) continue;
            String requestId = request.path("id").asText("");
            if (requestId.isBlank()) continue;
            JsonNode metadata = request.path("metadata");
            result.add(new PendingPermission(requestId, sessionId, request.path("permission").asText(""),
                    strings(request.path("patterns")), object(metadata),
                    firstText(metadata.path("title"), metadata.path("description"),
                            request.path("permission"))));
        }
        return List.copyOf(result);
    }

    List<AgentInfo> agents(JsonNode body) {
        JsonNode values = listBody(body);
        if (values == null) return null;
        List<AgentInfo> result = new ArrayList<>();
        for (JsonNode value : values) {
            String name = firstText(value.path("name"), value.path("id"));
            if (name.isBlank()) continue;
            result.add(new AgentInfo(name, blankToNull(value.path("mode").asText("")),
                    blankToNull(firstText(value.path("description"), value.path("prompt")))));
        }
        return List.copyOf(result);
    }

    List<UsageRecord> usage(JsonNode messages) {
        List<UsageRecord> result = new ArrayList<>();
        for (JsonNode message : messages) {
            JsonNode info = message.path("info");
            if (!"assistant".equalsIgnoreCase(role(message))) continue;
            String messageId = firstText(info.path("id"), message.path("id"));
            if (messageId.isBlank()) continue;
            JsonNode tokens = info.path("tokens");
            Long input = nullableLong(tokens.path("input"));
            Long output = nullableLong(tokens.path("output"));
            Long total = nullableLong(tokens.path("total"));
            BigDecimal cost = nullableDecimal(info.path("cost"));
            boolean reliable = input != null || output != null || total != null || cost != null;
            result.add(new UsageRecord(messageId, nullableText(info.path("providerID")),
                    nullableText(info.path("modelID")), input, output, total, cost,
                    nullableText(info.path("currency")), reliable));
        }
        return List.copyOf(result);
    }

    SessionStatus messageStatus(JsonNode messages) {
        boolean relevantMessage = false;
        int latestUserIndex = -1;
        int latestAssistantIndex = -1;
        JsonNode latestAssistant = null;
        int index = 0;
        for (JsonNode message : messages) {
            String role = role(message);
            if (!"assistant".equalsIgnoreCase(role) && !"user".equalsIgnoreCase(role)) {
                index++;
                continue;
            }
            relevantMessage = true;
            if ("user".equalsIgnoreCase(role)) latestUserIndex = index;
            if ("assistant".equalsIgnoreCase(role)) {
                latestAssistant = message;
                latestAssistantIndex = index;
            }
            index++;
        }
        if (latestAssistant != null && latestAssistantIndex > latestUserIndex) {
            JsonNode info = latestAssistant.path("info");
            if (!info.path("error").isMissingNode() && !info.path("error").isNull()) {
                return new SessionStatus("FAILED", errorDetail(info.path("error")));
            }
            JsonNode completed = info.path("time").path("completed");
            if (!completed.isMissingNode() && !completed.isNull()) return new SessionStatus("COMPLETED");
        }
        return relevantMessage ? new SessionStatus("RUNNING") : new SessionStatus("UNKNOWN");
    }

    String assistantText(JsonNode message) {
        StringBuilder text = new StringBuilder();
        JsonNode parts = message.path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if ("text".equalsIgnoreCase(part.path("type").asText()) && part.hasNonNull("text")) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(part.path("text").asText());
                }
            }
        }
        if (text.isEmpty() && message.hasNonNull("text")) text.append(message.path("text").asText());
        return text.toString();
    }

    String role(JsonNode message) {
        return message.path("info").path("role").asText(message.path("role").asText(""));
    }

    String errorDetail(JsonNode error) {
        String detail = firstText(error.path("message"), error.path("data").path("message"),
                error.path("name"), error.path("code"));
        return detail.isBlank() ? error.toString() : detail;
    }

    String firstText(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isValueNode()) {
                String value = candidate.asText("");
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }

    String bounded(String value) {
        if (value == null) return "";
        return value.length() <= 40_000
                ? value : value.substring(0, 40_000) + "\n… output truncated by Loopper …";
    }

    Map<String, Object> object(JsonNode value) {
        if (value == null || !value.isObject()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            result.put(entry.getKey(), jsonValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    private SessionPart monitorPart(JsonNode part, JsonNode message, int messageIndex, int partIndex) {
        String sourceType = part.path("type").asText("").toLowerCase();
        String id = part.path("id").asText("message-" + messageIndex + "-part-" + partIndex);
        String startedAt = startedAt(part.path("time").path("start"),
                part.path("state").path("time").path("start"),
                message.path("info").path("time").path("created"));
        if ("text".equals(sourceType)) {
            String content = bounded(part.path("text").asText(""));
            return content.isBlank() ? null
                    : new SessionPart(id, "OUTPUT", "模型输出", content, null, startedAt);
        }
        if ("reasoning".equals(sourceType) || "thinking".equals(sourceType)) {
            String content = firstText(part.path("text"), part.path("content"), part.path("reasoning"));
            return content.isBlank() ? null : new SessionPart(id, "THINKING", "Thinking", bounded(content),
                    part.path("state").asText(null), startedAt);
        }
        if ("tool".equals(sourceType) || "tool-call".equals(sourceType)
                || "tool_invocation".equals(sourceType)) {
            JsonNode state = part.path("state");
            String label = firstText(part.path("tool"), part.path("name"), state.path("title"));
            String content = toolContent(firstNode(state.path("input"), part.path("input"), part.path("arguments")),
                    firstNode(state.path("output"), part.path("output"), part.path("text")));
            String status = firstText(state.path("status"), part.path("status"));
            return new SessionPart(id, "TOOL", label.isBlank() ? "工具调用" : bounded(label),
                    bounded(content), bounded(status), startedAt);
        }
        return null;
    }

    private String toolContent(JsonNode input, JsonNode output) {
        String arguments = displayValue(input);
        String result = displayValue(output);
        if (arguments.isBlank()) return result;
        if (result.isBlank()) return "参数\n" + arguments;
        return "参数\n" + arguments + "\n\n输出\n" + result;
    }

    private String displayValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        return value.isTextual() ? value.asText() : value.toString();
    }

    private JsonNode firstNode(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null && !candidate.isMissingNode() && !candidate.isNull()) return candidate;
        }
        return null;
    }

    private String startedAt(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate == null || candidate.isMissingNode() || candidate.isNull()) continue;
            if (candidate.isNumber()) {
                long value = candidate.asLong();
                if (value <= 0) continue;
                return (value >= 10_000_000_000L
                        ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value)).toString();
            }
            if (candidate.isTextual() && !candidate.asText().isBlank()) return candidate.asText();
        }
        return null;
    }

    private List<String> strings(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) if (item.isValueNode()) result.add(item.asText(""));
        return List.copyOf(result);
    }

    private Object jsonValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (value.isObject()) return object(value);
        if (value.isArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonNode item : value) result.add(jsonValue(item));
            return Collections.unmodifiableList(result);
        }
        if (value.isBoolean()) return value.booleanValue();
        if (value.isIntegralNumber()) return value.longValue();
        if (value.isNumber()) return value.decimalValue();
        return value.asText("");
    }

    private Long nullableLong(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull() || !value.isNumber()
                ? null : value.longValue();
    }

    private BigDecimal nullableDecimal(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull() || !value.isNumber()
                ? null : value.decimalValue();
    }

    private String nullableText(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull() || value.asText("").isBlank()
                ? null : value.asText();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
