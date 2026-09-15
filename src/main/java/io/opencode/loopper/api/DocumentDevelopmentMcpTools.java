package io.opencode.loopper.api;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.service.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

final class DocumentDevelopmentMcpTools {
    private DocumentDevelopmentMcpTools() { }
    static List<McpServerFeatures.SyncToolSpecification> specifications(DocumentDevelopmentReads reads, ObjectMapper json) {
        return List.of(spec("get_development_task_guide", "Read this role's frozen project directory, stage objective, allowed paths and deliverables before planning or editing", reads, json, Map.of("scope", text())),spec("list_development_documents", "Read the immutable document index, source references and resource URIs", reads, json, Map.of("scope", text())),
                spec("list_development_sections", "Read a page of original document headings, not inferred requirements", reads, json,
                        Map.of("scope", text(), "fileId", text(), "offset", Map.of("type", "integer", "minimum", 0))),
                spec("list_development_requirements", "Page through immutable requirements and source identities for the signed software role", reads, json,
                        Map.of("scope", text(), "after", Map.of("type", "integer", "minimum", -1))),
                spec("read_development_requirement", "Read one complete frozen requirement, acceptance scenarios and source references", reads, json,
                        Map.of("scope", text(), "key", text())),
                spec("read_development_source", "Read one frozen document segment using its expected document hash", reads, json,
                        Map.of("scope", text(), "fileId", text(), "section", Map.of("type", "integer", "minimum", 0), "expectedSha256", text())));
    }
    private static Map<String, Object> text() { return Map.of("type", "string", "maxLength", 1024); }
    private static McpServerFeatures.SyncToolSpecification spec(String name, String description, DocumentDevelopmentReads reads,
            ObjectMapper json, Map<String, Object> properties) {
        var schema = Map.<String, Object>of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false);
        var tool = McpSchema.Tool.builder(name, schema).description(description).annotations(McpSchema.ToolAnnotations.builder()
                .readOnlyHint(true).destructiveHint(false).idempotentHint(true).openWorldHint(false).build()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            try {
                var args = request.arguments();
                if (args == null || !args.keySet().equals(properties.keySet())) throw invalid();
                String grant = string(args, "scope");
                Object value = switch (name) {
                    case "get_development_task_guide" -> reads.guide(grant);
                    case "list_development_documents" -> reads.documents(grant);
                    case "list_development_sections" -> reads.sections(grant, string(args, "fileId"), number(args, "offset"));
                    case "list_development_requirements" -> reads.index(grant, number(args, "after"));
                    case "read_development_requirement" -> reads.requirement(grant, string(args, "key"));
                    case "read_development_source" -> reads.source(grant, string(args, "fileId"), number(args, "section"), string(args, "expectedSha256"));
                    default -> throw invalid();
                };
                return result(json.writeValueAsString(value), false);
            } catch (BadRequestException failure) { return result(json.writeValueAsString(Map.of("code", failure.code(), "detail", failure.getMessage())), true); }
            catch (ConflictException failure) { return result(json.writeValueAsString(Map.of("code", failure.code(), "detail", failure.getMessage())), true); }
            catch (RuntimeException failure) { return result("{\"code\":\"DOCUMENT_READ_UNAVAILABLE\",\"detail\":\"冻结需求暂不可读，请检查当前角色与文档范围\"}", true); }
        }).build();
    }
    private static McpSchema.CallToolResult result(String body, boolean error) {
        return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(body))).isError(error).build();
    }
    private static String string(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof String value) || value.isBlank() || value.length() > 1024) throw invalid(); return value;
    }
    private static int number(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof Number value) || value.doubleValue() != value.intValue() || value.intValue() < -1 || value.intValue() > 10000000) throw invalid();
        return value.intValue();
    }
    private static BadRequestException invalid() { return new BadRequestException("DOCUMENT_READ_ARGUMENT_INVALID", "请按冻结需求工具的字段、类型和分页范围修正参数"); }
}
