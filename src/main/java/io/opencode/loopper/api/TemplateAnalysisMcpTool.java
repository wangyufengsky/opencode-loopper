package io.opencode.loopper.api;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.NotFoundException;
import io.opencode.loopper.service.TemplateCandidateSubmissionService;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Private typed tool adapter; candidate compilation and receipts belong to the template workflow. */
final class TemplateAnalysisMcpTool {
    private TemplateAnalysisMcpTool() { }

    static McpServerFeatures.SyncToolSpecification specification(TemplateCandidateSubmissionService submissions, ObjectMapper json) {
        var tool = McpSchema.Tool.builder(InternalMcpContractCatalog.TEMPLATE_TOOL, schema())
                .description("Submit the complete frozen template batch. Fix REJECTED candidates in this same session; stop after ACCEPTED.")
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(false).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> call(submissions, json, request.arguments())).build();
    }

    static McpSchema.CallToolResult call(TemplateCandidateSubmissionService service, ObjectMapper json, Map<String, Object> arguments) {
        try {
            var validation = InternalMcpRequestValidator.validate(arguments);
            if (!validation.valid()) return result(json, Map.of("action", "FIX_AND_RESUBMIT", "problems", validation.problems()), true);
            var request = validation.value();
            String response = service.submit(request.runId(), request.idempotencyKey(), request.expectedSubmissionRevision(),
                    json.writeValueAsString(request.candidate()));
            Map<String, Object> value = json.readValue(response, new tools.jackson.core.type.TypeReference<>() { });
            return result(json, value, !"ACCEPTED".equals(value.get("outcome")));
        } catch (BadRequestException invalid) {
            return error(json, invalid.code(), invalid.getMessage(), "FIX_AND_RESUBMIT");
        } catch (ConflictException stale) {
            return error(json, stale.code(), stale.getMessage(), stale.code().contains("IDEMPOTENCY") ? "FIX_AND_RESUBMIT" : "STOP");
        } catch (NotFoundException absent) {
            return error(json, "TEMPLATE_BATCH_NOT_FOUND", "冻结批次不存在", "STOP");
        } catch (RuntimeException unavailable) {
            return error(json, "TEMPLATE_SUBMISSION_UNAVAILABLE", "提交暂不可用，保留原请求键与候选后重试", "RETRY_SAME_REQUEST");
        }
    }

    private static McpSchema.CallToolResult error(ObjectMapper json, String code, String detail, String action) {
        return result(json, Map.of("errorCode", code, "detail", detail, "action", action), true);
    }
    private static McpSchema.CallToolResult result(ObjectMapper json, Map<String, Object> value, boolean error) {
        return McpSchema.CallToolResult.builder().addTextContent(json.writeValueAsString(value))
                .structuredContent(value).isError(error).build();
    }

    static Map<String, Object> schema() {
        var text = Map.<String, Object>of("type", "string", "minLength", 1);
        var finding = object(Map.of("severity", Map.of("type", "string", "enum", List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")),
                "side", Map.of("type", "string", "enum", List.of("BEFORE", "AFTER")), "line", Map.of("type", "integer", "minimum", 1),
                "title", text, "detail", text, "recommendation", text));
        var review = object(Map.of("unitId", text, "summary", text, "findings", array(finding), "limitations", array(text)));
        var assessment = object(Map.of("level", Map.of("type", "integer", "minimum", 0, "maximum", 4), "reason", text, "evidenceIds", array(text)));
        var contributor = object(Map.of("identity", text, "summary", text, "value", assessment, "difficulty", assessment,
                "quality", assessment, "maintenance", assessment));
        return object(Map.of("runId", text, "idempotencyKey", Map.of("type", "string", "minLength", 1, "maxLength", 128),
                "expectedSubmissionRevision", Map.of("type", "integer", "minimum", 0),
                "candidate", Map.of("anyOf", List.of(object(Map.of("reviews", array(review))), contributor))));
    }
    private static Map<String, Object> array(Map<String, Object> items) { return Map.of("type", "array", "items", items); }
    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", properties.keySet().stream().sorted().toList(), "additionalProperties", false);
    }
}
