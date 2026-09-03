package io.opencode.loopper.runtime;

import static io.opencode.loopper.runtime.OpenCodeHttpTransport.directoryUri;
import static io.opencode.loopper.runtime.OpenCodeClient.*;
import static io.opencode.loopper.runtime.OpenCodeHttpTransport.sessionUri;
import io.opencode.loopper.domain.SessionFailure;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** Native command discovery and invocation; it never decides a business lifecycle. */
final class OpenCodeCommandTransport {
    private static final Pattern RUN_ID = Pattern.compile("(?i)\\\"runId\\\"\\s*:\\s*\\\"([^\\\"]{1,512})\\\"");
    private final Supplier<RestClient> client;
    private final Function<OpenCodeClient.OpenCodeSession, RestClient> sessions;
    private final Function<OpenCodeClient.OpenCodeSession, RestClient> commands;
    private final OpenCodeResponseParser responses = new OpenCodeResponseParser();
    private final OpenCodeSessionCommandGate gate = new OpenCodeSessionCommandGate();

    OpenCodeCommandTransport(Supplier<RestClient> client,
                             Function<OpenCodeClient.OpenCodeSession, RestClient> sessions) {
        this(client, sessions, sessions);
    }

    OpenCodeCommandTransport(Supplier<RestClient> client,
                             Function<OpenCodeSession, RestClient> sessions,
                             Function<OpenCodeSession, RestClient> commands) {
        this.client = client;
        this.sessions = sessions;
        this.commands = commands;
    }

    OpenCodeClient.CommandCapabilityProbe capabilities(Path worktree) {
        try {
            Path canonical = worktree.toRealPath();
            JsonNode body = client.get().get().uri(uri -> directoryUri(uri, "/command", canonical))
                    .retrieve().body(JsonNode.class);
            JsonNode commands = responses.listBody(body);
            if (commands == null) return unavailable(OpenCodeClient.CapabilityState.UNKNOWN,
                    "OpenCode 返回了无法识别的命令清单");
            List<String> names = new ArrayList<>();
            for (JsonNode command : commands) {
                String name = command.isTextual() ? command.asText() : command.path("name").asText(null);
                if (name != null && !name.isBlank()) names.add(name);
            }
            return new OpenCodeClient.CommandCapabilityProbe(OpenCodeClient.CapabilityState.AVAILABLE, names, null);
        } catch (RestClientResponseException failure) {
            return unavailable(failure.getStatusCode().value() == 404
                            ? OpenCodeClient.CapabilityState.UNAVAILABLE : OpenCodeClient.CapabilityState.UNKNOWN,
                    failure.getStatusCode().value() == 404 ? "当前 OpenCode 不提供命令发现接口"
                            : "OpenCode 命令检测失败：" + responses.bounded(failure.getMessage()));
        } catch (RuntimeException | java.io.IOException failure) {
            return unavailable(OpenCodeClient.CapabilityState.UNKNOWN,
                    "OpenCode 命令检测失败：" + responses.bounded(failure.getMessage()));
        }
    }

    OpenCodeClient.ToolCapabilityProbe tools(Path worktree) {
        try {
            Path canonical = worktree.toRealPath();
            JsonNode body = client.get().get().uri(uri -> directoryUri(uri, "/experimental/tool/ids", canonical))
                    .retrieve().body(JsonNode.class);
            JsonNode ids = responses.listBody(body);
            if (ids == null) return new ToolCapabilityProbe(CapabilityState.UNKNOWN, List.of(),
                    "OpenCode returned an invalid tool-id response");
            List<String> result = new ArrayList<>();
            for (JsonNode id : ids) if (id.isTextual() && !id.asText().isBlank()) result.add(id.asText());
            return new ToolCapabilityProbe(CapabilityState.AVAILABLE, result, null);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) {
                return new ToolCapabilityProbe(CapabilityState.UNAVAILABLE, List.of(),
                        "OpenCode does not expose /experimental/tool/ids");
            }
            return new ToolCapabilityProbe(CapabilityState.UNKNOWN, List.of(), responses.bounded(failure.getMessage()));
        } catch (RuntimeException | java.io.IOException failure) {
            return new ToolCapabilityProbe(CapabilityState.UNKNOWN, List.of(), responses.bounded(failure.getMessage()));
        }
    }

    List<AgentInfo> agents() {
        try {
            JsonNode body = client.get().get().uri("/agent").retrieve().body(JsonNode.class);
            List<AgentInfo> result = responses.agents(body);
            if (result == null) throw new SessionFailure("OPENCODE_AGENT_INVALID_RESPONSE",
                    "OpenCode did not return an agent list");
            return result;
        } catch (SessionFailure failure) { throw failure; }
        catch (RuntimeException failure) {
            throw new SessionFailure("OPENCODE_AGENT_LIST_FAILED", failure.getMessage());
        }
    }

    SessionStatus accountingStatus(OpenCodeSession session, SessionStatus status, java.util.Set<String> identities) {
        if (identities.isEmpty()) return status;
        JsonNode raw = sessions.apply(session).get().uri(uri -> sessionUri(uri, "/session/{id}/message", session))
                .retrieve().body(JsonNode.class);
        return raw == null ? status : OpenCodeAccountingMessageFilter.status(
                raw.isArray() ? raw : raw.path("data"), identities, status);
    }

    OpenCodeClient.CommandResult execute(OpenCodeClient.OpenCodeSession session,
                                          OpenCodeClient.CommandRequest request) {
        return gate.command(session, request.messageId(), () -> invoke(session, request));
    }

    <T> T businessAbort(OpenCodeSession session, Supplier<T> action) {
        return gate.abort(session, action);
    }

    private CommandResult invoke(OpenCodeSession session, CommandRequest request) {
        try {
            JsonNode response = commands.apply(session).post()
                    .uri(uri -> sessionUri(uri, "/session/{id}/command", session))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("command", request.command(), "agent", OpenCodeAccountingAgent.NAME, "arguments", request.arguments(),
                            "messageID", request.messageId()))
                    .retrieve().body(JsonNode.class);
            if (response == null) throw new SessionFailure("OPENCODE_COMMAND_EMPTY_RESPONSE", "OpenCode 未返回统计命令结果");
            JsonNode error = response.path("info").path("error");
            if (error.isMissingNode() || error.isNull()) error = response.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new SessionFailure("OPENCODE_COMMAND_FAILED", responses.errorDetail(error));
            }
            inspectCommandMessages(session, request.messageId());
            return new OpenCodeClient.CommandResult(runId(response, 0), responses.bounded(response.toString()));
        } catch (SessionFailure failure) { throw failure; }
        catch (RuntimeException failure) {
            throw new SessionFailure("OPENCODE_COMMAND_FAILED", failure.getMessage());
        }
    }

    SessionTranscript transcript(OpenCodeSession session, String messageId) {
        JsonNode messages = messages(session);
        var selected = new tools.jackson.databind.ObjectMapper().createArrayNode();
        if (messages != null) for (JsonNode message : messages) {
            JsonNode info = message.path("info");
            if (messageId.equals(info.path("parentID").asText(info.path("parentId").asText(null)))) {
                selected.add(message);
            }
        }
        return responses.transcript(selected);
    }

    /** Called only while the coordinator still owns the pre-business barrier. Never abort a later user turn. */
    boolean cancel(OpenCodeSession session, String messageId) {
        try { return cancelRemote(session, messageId); }
        finally { gate.cancelled(session, messageId); }
    }

    private boolean cancelRemote(OpenCodeSession session, String messageId) {
        JsonNode messages = messages(session);
        String lastUser = null;
        if (messages != null) for (JsonNode message : messages) {
            if ("user".equals(message.path("info").path("role").asText())) {
                lastUser = message.path("info").path("id").asText(null);
            }
        }
        if (!messageId.equals(lastUser)) return false;
        Boolean stopped = sessions.apply(session).post()
                .uri(uri -> sessionUri(uri, "/session/{id}/abort", session)).retrieve().body(Boolean.class);
        return Boolean.TRUE.equals(stopped);
    }

    private JsonNode messages(OpenCodeSession session) {
        return responses.listBody(sessions.apply(session).get()
                .uri(uri -> sessionUri(uri, "/session/{id}/message", session)).retrieve().body(JsonNode.class));
    }

    private void inspectCommandMessages(OpenCodeSession session, String messageId) {
        JsonNode messages;
        try {
            messages = sessions.apply(session).get().uri(uri -> sessionUri(uri, "/session/{id}/message", session))
                    .retrieve().body(JsonNode.class);
        } catch (RuntimeException unavailable) { return; }
        if (messages == null || !messages.isArray()) return;
        for (JsonNode message : messages) {
            if (!messageId.equals(message.path("info").path("parentID").asText())) continue;
            JsonNode error = message.path("info").path("error");
            if (!error.isMissingNode() && !error.isNull()) throw new SessionFailure("OPENCODE_COMMAND_FAILED", responses.errorDetail(error));
            for (JsonNode part : message.path("parts")) {
                if ("tool".equals(part.path("type").asText()) && "error".equals(part.path("state").path("status").asText())) {
                    throw new SessionFailure("OPENCODE_COMMAND_TOOL_FAILED", part.path("state").path("error").asText("统计工具执行失败"));
                }
            }
        }
    }

    private String runId(JsonNode node, int depth) {
        if (node == null || depth > 8) return null;
        for (String field : List.of("runId", "runID")) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) return value.asText();
        }
        if (node.isTextual()) {
            var match = RUN_ID.matcher(node.asText());
            return match.find() ? match.group(1) : null;
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode value : node) {
                String found = runId(value, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private OpenCodeClient.CommandCapabilityProbe unavailable(OpenCodeClient.CapabilityState state, String reason) {
        return new OpenCodeClient.CommandCapabilityProbe(state, List.of(), reason);
    }
}
