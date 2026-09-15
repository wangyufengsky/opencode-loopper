package io.opencode.loopper.api;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.service.*;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;

final class DocumentReviewGuideMcpTools {
    private DocumentReviewGuideMcpTools() { }
    static List<McpServerFeatures.SyncToolSpecification> specifications(DocumentReviewGuideService service, ObjectMapper json) {
        var text = Map.<String, Object>of("type", "string", "minLength", 1, "maxLength", 128);
        return List.of(tool("get_document_review_work", "List this batch's original headings, read receipts and existing requirement keys. Read the actual original sections before assessing.",
                Map.of("runId", text, "offset", Map.of("type", "integer", "minimum", 0), "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100)),
                args -> service.work((String) args.get("runId"), integer(args, "offset"), integer(args, "limit")), json),
                tool("check_document_review_candidate", "Read-only candidate preflight. Returns exact validation problems, never accepts or consumes a candidate submission. Fix problems before checking again.",
                        Map.of("runId", text, "candidate", Map.of("anyOf", List.of(candidateSchema(io.opencode.loopper.domain.MachineCandidateKind.DOCUMENT_CODE_ASSESSMENT_V2), candidateSchema(io.opencode.loopper.domain.MachineCandidateKind.DOCUMENT_CODE_REVIEW_V2)))), args -> service.check((String) args.get("runId"), candidate(args)), json));
    }
    @SuppressWarnings("unchecked") private static Object candidateSchema(io.opencode.loopper.domain.MachineCandidateKind kind) {
        var envelope = io.opencode.loopper.runtime.InternalMcpContractCatalog.inputSchema(kind);
        return ((Map<String, Object>) envelope.get("properties")).get("candidate");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> candidate(Map<String, Object> args) {
        if (!(args.get("candidate") instanceof Map<?, ?> value)) throw new BadRequestException("DOCUMENT_ARGUMENT_INVALID", "candidate 必须为对象");
        return (Map<String, Object>) value;
    }
    private static int integer(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof Number value) || value.doubleValue() != value.intValue())
            throw new BadRequestException("DOCUMENT_ARGUMENT_INVALID", key + " 必须为整数");
        return value.intValue();
    }
    private static McpServerFeatures.SyncToolSpecification tool(String name, String description, Map<String, Object> properties,
            Function<Map<String, Object>, Map<String, Object>> action, ObjectMapper json) {
        var tool = McpSchema.Tool.builder(name, Map.of("type", "object", "properties", properties,
                        "required", properties.keySet().stream().sorted().toList(), "additionalProperties", false)).description(description)
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false).idempotentHint(true).openWorldHint(false).build()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            try {
                var args = request.arguments();
                if (args == null || !args.keySet().equals(properties.keySet()) || !(args.get("runId") instanceof String id) || id.isBlank() || id.length() > 128)
                    throw new BadRequestException("DOCUMENT_ARGUMENT_INVALID", "请按工具结构提供完整参数");
                return result(json, action.apply(args), false);
            } catch (BadRequestException invalid) { return result(json, Map.of("code", invalid.code(), "detail", invalid.getMessage()), true);
            } catch (ConflictException stale) { return result(json, Map.of("code", stale.code(), "detail", stale.getMessage()), true);
            } catch (RuntimeException unavailable) { return result(json, Map.of("code", "DOCUMENT_GUIDE_UNAVAILABLE", "detail", "当前批次信息不可用，请核对运行身份"), true); }
        }).build();
    }
    private static McpSchema.CallToolResult result(ObjectMapper json, Map<String, Object> value, boolean error) {
        return McpSchema.CallToolResult.builder().structuredContent(value).addTextContent(json.writeValueAsString(value)).isError(error).build();
    }
}
