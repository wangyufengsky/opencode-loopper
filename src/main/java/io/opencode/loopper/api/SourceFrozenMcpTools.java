package io.opencode.loopper.api;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.service.*;
import java.util.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Static source tools expose only server-frozen records through an active role run. */
final class SourceFrozenMcpTools {
    private SourceFrozenMcpTools() { }
    static List<McpServerFeatures.SyncToolSpecification> specifications(SourceModelReads reads, ObjectMapper json) {
        return List.of(spec("get_source_design_work", "Read assigned immutable source work and progress", reads, json,
                        fields("runId", text())),
                spec("list_source_template_files", "List immutable project source metadata", reads, json,
                        fields("runId", text(), "after", integer(-1, 100000), "limit", integer(1, 100))),
                spec("read_source_template_file", "Read frozen source and register independent evidence", reads, json,
                        fields("runId", text(), "path", text(), "expectedSha256", text(), "startLine", integer(1, 10000000), "limit", integer(1, 200))),
                spec("list_source_design_results", "List completed modules to review cross-module consistency", reads, json,
                        fields("runId", text(), "after", integer(-1, 100000), "limit", integer(1, 100))),
                spec("read_source_design_result", "Read one immutable design result part", reads, json,
                        fields("runId", text(), "resultId", text(), "expectedSha256", text(), "part", integer(0, 1000))));
    }
    private static McpServerFeatures.SyncToolSpecification spec(String name, String description,
            SourceModelReads reads, ObjectMapper json, Map<String, Object> properties) {
        var schema = Map.<String, Object>of("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()), "additionalProperties", false);
        var tool = McpSchema.Tool.builder(name, schema).description(description)
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            try {
                var args = request.arguments();
                if (args == null || !args.keySet().equals(properties.keySet())) throw invalid();
                String id = string(args, "runId");
                Object value = switch (name) {
                    case "get_source_design_work" -> reads.work(id);
                    case "list_source_template_files" -> reads.files(id, number(args, "after"), number(args, "limit"));
                    case "read_source_template_file" -> reads.read(id, string(args, "path"), string(args, "expectedSha256"), number(args, "startLine"), number(args, "limit"));
                    case "list_source_design_results" -> reads.results(id, number(args, "after"), number(args, "limit"));
                    case "read_source_design_result" -> reads.result(id, string(args, "resultId"), string(args, "expectedSha256"), number(args, "part"));
                    default -> throw invalid();
                };
                Map<String, Object> result = json.convertValue(value, new TypeReference<>() { });
                return result(json, result, false);
            } catch (BadRequestException invalid) {
                return result(json, Map.of("errorCode", invalid.code(), "detail", invalid.getMessage()), true);
            } catch (ConflictException stale) {
                return result(json, Map.of("errorCode", stale.code(), "detail", stale.getMessage()), true);
            } catch (RuntimeException unavailable) {
                return result(json, Map.of("errorCode", "SOURCE_FROZEN_READ_UNAVAILABLE", "detail", "冻结读取未成功，不得将缺失证据视为已完成"), true);
            }
        }).build();
    }
    private static String string(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof String value) || value.length() > 1024) throw invalid();
        return value;
    }
    private static int number(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof Number value) || value.doubleValue() != value.intValue()) throw invalid();
        return value.intValue();
    }
    private static BadRequestException invalid() { return new BadRequestException("SOURCE_READ_PARAMETERS_INVALID", "冻结读取参数缺失或无效"); }
    private static McpSchema.CallToolResult result(ObjectMapper json, Map<String, Object> value, boolean error) {
        return McpSchema.CallToolResult.builder().structuredContent(value).addTextContent(json.writeValueAsString(value)).isError(error).build();
    }
    private static Map<String, Object> text() { return Map.of("type", "string", "maxLength", 1024); }
    private static Map<String, Object> integer(int min, int max) { return Map.of("type", "integer", "minimum", min, "maximum", max); }
    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
