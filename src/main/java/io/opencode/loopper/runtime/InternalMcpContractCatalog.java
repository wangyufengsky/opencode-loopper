package io.opencode.loopper.runtime;

import java.util.List;
import java.util.Map;

/** Stable, server-owned contract for the private candidate-submission MCP. */
public final class InternalMcpContractCatalog {
    public static final String ENDPOINT_PATH = "/api/internal-mcp-streamable";
    public static final String TOOL_NAME = "submit_candidate";

    private InternalMcpContractCatalog() { }

    public static List<String> toolNames() {
        return List.of(TOOL_NAME);
    }

    public static Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("runId", "idempotencyKey", "candidate", "expectedSubmissionRevision"),
                "properties", Map.of(
                        "runId", Map.of("type", "string", "minLength", 1),
                        "idempotencyKey", Map.of("type", "string", "minLength", 1),
                        "candidate", Map.of(
                                "type", "object",
                                "description", "Candidate payload for the active server-owned submission contract",
                                "maxProperties", 64),
                        "expectedSubmissionRevision", Map.of("type", "integer", "minimum", 0)));
    }
}
