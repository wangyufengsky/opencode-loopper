package io.opencode.loopper.api;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Backward-compatible JSON-RPC endpoint. Protocol-native Streamable HTTP MCP is owned by
 * Spring AI at {@code /api/mcp-streamable}; both surfaces delegate to {@link LoopperMcpTools}.
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {
    private static final String PROTOCOL_VERSION = "2025-03-26";
    private final McpTokenProvider token;
    private final ObjectMapper json;
    private final LoopperMcpTools tools;

    public McpController(McpTokenProvider token, ObjectMapper json, LoopperMcpTools tools) {
        this.token = token;
        this.json = json;
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> call(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestBody McpRequest request) {
        JsonNode id = request == null ? null : request.id();
        if (!token.matches(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(id, -32001, "MCP bearer token required"));
        }
        if (request == null || !"2.0".equals(request.jsonrpc()) || request.method() == null || request.method().isBlank()) {
            return ResponseEntity.badRequest().body(error(id, -32600, "Invalid JSON-RPC request"));
        }
        // MCP notifications deliberately have no JSON-RPC response body.
        if ("notifications/initialized".equals(request.method())) {
            return ResponseEntity.accepted().build();
        }
        try {
            Object result = switch (request.method()) {
                case "initialize" -> initialize(request.params());
                case "tools/list" -> Map.of("tools", tools());
                case "tools/call" -> callTool(request.params());
                default -> null;
            };
            if (result == null) return ResponseEntity.ok(error(id, -32601, "Method not found"));
            return ResponseEntity.ok(response(id, result));
        } catch (RuntimeException exception) {
            // A known tool may legitimately reject business input. Keep that inside a valid MCP
            // tool-result envelope instead of turning an application conflict into fake model output.
            return ResponseEntity.ok(response(id, toolError(errorText(exception))));
        }
    }

    private Map<String, Object> initialize(JsonNode params) {
        String requested = params == null ? null : params.path("protocolVersion").asText(null);
        if (requested != null && !requested.isBlank() && !PROTOCOL_VERSION.equals(requested)) {
            // The server is deliberately explicit rather than silently negotiating an unknown version.
            throw new BadRequestException("MCP_PROTOCOL_UNSUPPORTED", "Unsupported MCP protocol version: " + requested);
        }
        return Map.of("protocolVersion", PROTOCOL_VERSION, "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "opencode-loopper", "version", "0.1.6"),
                "instructions", "Designer access is read-only. Proposals synchronize the session-bound DRAFT_READY LoopSpec; a human must confirm before create_task.");
    }

    private Object callTool(JsonNode params) {
        String name = requiredText(params, "name", "MCP_TOOL_NAME_REQUIRED");
        JsonNode arguments = params == null ? null : params.path("arguments");
        if (arguments == null || arguments.isMissingNode() || !arguments.isObject()) {
            throw new BadRequestException("MCP_TOOL_ARGUMENTS_REQUIRED", "Tool arguments must be a JSON object");
        }
        Object value = switch (name) {
            case "get_project_context" -> tools.getProjectContext(requiredText(arguments, "projectId", "PROJECT_ID_REQUIRED"));
            case "propose_loop_spec" -> propose(arguments);
            case "validate_loop_spec" -> validate(arguments);
            case "create_task" -> tools.createTask(requiredText(arguments, "draftId", "DRAFT_ID_REQUIRED"));
            case "start_task" -> tools.startTask(requiredText(arguments, "taskId", "TASK_ID_REQUIRED"));
            case "get_task_status" -> tools.getTaskStatus(requiredText(arguments, "taskId", "TASK_ID_REQUIRED"));
            default -> throw new BadRequestException("MCP_TOOL_NOT_FOUND", "Unknown MCP tool: " + name);
        };
        return toolResult(value);
    }

    private Map<String, Object> propose(JsonNode args) {
        String designerSessionId = requiredText(args, "designerSessionId", "DESIGNER_SESSION_ID_REQUIRED");
        String projectId = requiredText(args, "projectId", "PROJECT_ID_REQUIRED");
        JsonNode source = args.get("spec");
        if (source == null || !source.isObject()) {
            throw new BadRequestException("LOOPSPEC_REQUIRED", "spec must be a complete LoopSpec JSON object");
        }
        LoopSpec proposal;
        try {
            proposal = json.treeToValue(source, LoopSpec.class);
        } catch (JacksonException exception) {
            throw new BadRequestException("LOOPSPEC_INVALID", "spec cannot be parsed: " + exception.getOriginalMessage());
        }
        return tools.proposeLoopSpec(designerSessionId, projectId, proposal);
    }

    private Map<String, Object> validate(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) return Map.of("valid", false, "errors", List.of("arguments: must be a JSON object"));
        try {
            String draftId = optionalText(node, "draftId");
            LoopSpec spec;
            Long version;
            if (draftId != null) {
                JsonNode versionNode = node.get("version");
                version = versionNode != null && versionNode.canConvertToLong() ? versionNode.asLong() : null;
                spec = null;
            } else {
                JsonNode source = node.get("spec");
                if (source == null || source.isMissingNode() || source.isNull()) return Map.of("valid", false, "errors", List.of("spec or draftId/version is required"));
                spec = json.treeToValue(source, LoopSpec.class);
                version = null;
            }
            return tools.validateLoopSpec(spec, draftId, version);
        } catch (JacksonException exception) {
            return Map.of("valid", false, "errors", List.of("spec: " + exception.getOriginalMessage()));
        }
    }

    private Map<String, Object> toolResult(Object value) {
        try {
            return Map.of("content", List.of(Map.of("type", "text", "text", json.writeValueAsString(value))), "structuredContent", value,
                    "isError", false);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize MCP tool response", exception);
        }
    }

    private Map<String, Object> toolError(String detail) {
        return Map.of("content", List.of(Map.of("type", "text", "text", detail)), "isError", true);
    }

    private Map<String, Object> response(JsonNode id, Object result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("result", result);
        return body;
    }

    private Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("error", Map.of("code", code, "message", message));
        return body;
    }

    private List<Map<String, Object>> tools() {
        return List.of(
                tool("get_project_context", "Read registered project context without file-write authority", Map.of("projectId", stringSchema()), List.of("projectId")),
                tool("propose_loop_spec", "Synchronize a complete DRAFT_READY LoopSpec into the draft bound to a read-only Designer session; human confirmation is still required", Map.of("designerSessionId", stringSchema(), "projectId", stringSchema(), "spec", Map.of("type", "object")), List.of("designerSessionId", "projectId", "spec")),
                tool("validate_loop_spec", "Validate a LoopSpec v1 supplied as spec, or a persisted draftId with its exact version", Map.of("spec", Map.of("type", "object"), "draftId", stringSchema(), "version", Map.of("type", "integer", "minimum", 0)), List.of()),
                tool("create_task", "Create or return the one task for a CONFIRMED draft; never auto-confirms and is idempotent", Map.of("draftId", stringSchema()), List.of("draftId")),
                tool("start_task", "Start a task whose contract and execution workspace are already prepared", Map.of("taskId", stringSchema()), List.of("taskId")),
                tool("get_task_status", "Read task, stage, attempt and layered error state", Map.of("taskId", stringSchema()), List.of("taskId")));
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
        return Map.of("name", name, "description", description,
                "inputSchema", Map.of("type", "object", "additionalProperties", false, "properties", properties, "required", required));
    }

    private Map<String, Object> stringSchema() { return Map.of("type", "string", "minLength", 1); }
    private String requiredText(JsonNode node, String field, String code) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) throw new BadRequestException(code, field + " is required");
        return value;
    }
    private String optionalText(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || !node.path(field).isTextual()) return null;
        String value = node.path(field).asText().trim();
        return value.isEmpty() ? null : value;
    }
    private String errorText(RuntimeException exception) {
        if (exception instanceof BadRequestException known) return known.code() + ": " + known.getMessage();
        if (exception instanceof ConflictException known) return known.code() + ": " + known.getMessage();
        return exception.getMessage() == null ? "MCP tool failed" : exception.getMessage();
    }

    public record McpRequest(String jsonrpc, JsonNode id, String method, JsonNode params) { }
}
