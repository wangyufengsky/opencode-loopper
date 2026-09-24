package io.opencode.loopper.api;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.service.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Transport for the frozen-code reader; authorization and evidence receipts remain in the service. */
final class SnapshotReviewMcpTools {
    private SnapshotReviewMcpTools() { }
    static List<McpServerFeatures.SyncToolSpecification> specifications(SnapshotReviewReads reads, ObjectMapper json) {
        return List.of(tool("list_snapshot_review_results", fields("runId", "offset", "limit"), reads, json), tool("get_snapshot_review_work", fields("runId", "offset", "limit"), reads, json),
                tool("get_snapshot_review_groups", fields("runId", "offset", "limit"), reads, json),
                tool("list_snapshot_review_code", fields("runId", "version", "after", "limit"), reads, json),
                tool("read_snapshot_review_code", fields("runId", "version", "path", "blob", "startLine", "limit"), reads, json),
                tool("search_snapshot_review_code", fields("runId", "version", "path", "blob", "query", "afterLine"), reads, json),
                tool("read_snapshot_review_result", fields("runId", "batchId"), reads, json));
    }
    private static McpServerFeatures.SyncToolSpecification tool(String name, Map<String, Object> fields, SnapshotReviewReads reads, ObjectMapper json) {
        var tool = McpSchema.Tool.builder(name, Map.of("type", "object", "properties", fields, "required", List.copyOf(fields.keySet()), "additionalProperties", false))
                .description("Read bounded frozen review evidence. runId is this model's batch ID; version must be an exact granted SHA. Reading an index is not analysis coverage.")
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false).idempotentHint(true).openWorldHint(false).build()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            try {
                var a = request.arguments();
                if (a == null || !a.keySet().equals(fields.keySet())) throw new BadRequestException("SNAPSHOT_PARAMETERS_INVALID", "读取参数不完整");
                String id = text(a, "runId");
                reads.requireTool(id, name);
                Object value = switch (name) {
                    case "list_snapshot_review_results" -> reads.results(id, number(a, "offset"), number(a, "limit"));
                    case "get_snapshot_review_work" -> reads.guide(id, number(a, "offset"), number(a, "limit"));
                    case "get_snapshot_review_groups" -> reads.groups(id, number(a, "offset"), number(a, "limit"));
                    case "list_snapshot_review_code" -> reads.files(id, text(a, "version"), text(a, "after"), number(a, "limit"));
                    case "read_snapshot_review_code" -> reads.read(id, text(a, "version"), text(a, "path"), text(a, "blob"), number(a, "startLine"), number(a, "limit"));
                    case "search_snapshot_review_code" -> reads.search(id, text(a, "version"), text(a, "path"), text(a, "blob"), text(a, "query"), number(a, "afterLine"));
                    case "read_snapshot_review_result" -> reads.result(id, text(a, "batchId"));
                    default -> throw new IllegalArgumentException();
                };
                return result(json, value, false);
            } catch (BadRequestException e) { return result(json, Map.of("errorCode", e.code(), "detail", e.getMessage()), true); }
            catch (ConflictException e) { return result(json, Map.of("errorCode", e.code(), "detail", e.getMessage()), true); }
            catch (RuntimeException e) { return result(json, Map.of("errorCode", "SNAPSHOT_READ_UNAVAILABLE", "detail", "冻结读取未成功，不能将证据缺失视为通过"), true); }
        }).build();
    }
    private static McpSchema.CallToolResult result(ObjectMapper json, Object value, boolean error) {
        Map<String, Object> fields = json.convertValue(value, new tools.jackson.core.type.TypeReference<>() { });
        return McpSchema.CallToolResult.builder().structuredContent(fields).addTextContent(json.writeValueAsString(fields)).isError(error).build();
    }
    private static Map<String, Object> fields(String... keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) result.put(key, Set.of("offset", "limit", "startLine", "afterLine").contains(key)
                ? Map.of("type", "integer", "minimum", 0) : Map.of("type", "string", "maxLength", 1024));
        return result;
    }
    private static String text(Map<String, Object> fields, String key) {
        if (!(fields.get(key) instanceof String s) || s.length() > 1024) throw new IllegalArgumentException(); return s;
    }
    private static int number(Map<String, Object> fields, String key) {
        if (!(fields.get(key) instanceof Number n) || n.doubleValue() != n.intValue()) throw new IllegalArgumentException(); return n.intValue();
    }
}
