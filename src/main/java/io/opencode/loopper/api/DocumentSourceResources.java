package io.opencode.loopper.api;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.service.*;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Resource and tool transports use the same live role authorization and frozen content service. */
@Component
public final class DocumentSourceResources {
    public static final String TEMPLATE = "loopper-document://{role}/{scope}/{file}/{section}";
    public static final String TOOL = "read_document_resource";
    private final DocumentDevelopmentReads development;
    private final DocumentFrozenReadService review;
    private final ObjectMapper json;
    public DocumentSourceResources(DocumentDevelopmentReads development, DocumentFrozenReadService review, ObjectMapper json) {
        this.development = development; this.review = review; this.json = json;
    }
    public McpSchema.ReadResourceResult read(String value) {
        URI uri;
        try { uri = URI.create(value); } catch (RuntimeException invalid) { throw invalid(); }
        if (uri.getHost() == null || uri.getRawPath() == null || value.length() > 2048 || !"loopper-document".equals(uri.getScheme()) || uri.getQuery() != null
                || uri.getFragment() != null || uri.getUserInfo() != null || uri.getPort() != -1) throw invalid();
        String[] parts = uri.getRawPath().split("/", -1);
        if (parts.length != 4 || java.util.Arrays.stream(parts).skip(1).anyMatch(part -> !part.matches("[A-Za-z0-9_.-]+"))) throw invalid();
        Object result = switch (uri.getHost()) {
            case "development" -> development.resource(parts[1], parts[2], parts[3]);
            case "review" -> review.resource(parts[1], parts[2], parts[3]);
            default -> throw invalid();
        };
        return new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents(value, "application/json", json.writeValueAsString(result), null)), null);
    }
    McpServerFeatures.SyncResourceTemplateSpecification specification() {
        return new McpServerFeatures.SyncResourceTemplateSpecification(McpSchema.ResourceTemplate.builder(TEMPLATE, "frozen_requirement_document")
                .description("Authorized frozen document index and paged sections; no global document listing").build(),
                (exchange, request) -> read(request.uri()));
    }
    McpServerFeatures.SyncToolSpecification tool() {
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of("uri", Map.of("type", "string", "maxLength", 2048)),
                "required", List.of("uri"), "additionalProperties", false);
        var tool = McpSchema.Tool.builder(TOOL, schema).description("Read one granted document resource; use returned pagination to continue")
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false).openWorldHint(false).build()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            try {
                if (request.arguments() == null || !request.arguments().keySet().equals(java.util.Set.of("uri"))
                        || !(request.arguments().get("uri") instanceof String uri)) throw invalid();
                return McpSchema.CallToolResult.builder().addTextContent(json.writeValueAsString(read(uri))).isError(false).build();
            } catch (RuntimeException failure) {
                return McpSchema.CallToolResult.builder().addTextContent("原文资源不可读，请核对本角色的来源许可、版本及分页位置").isError(true).build();
            }
        }).build();
    }
    private static BadRequestException invalid() { return new BadRequestException("DOCUMENT_RESOURCE_INVALID", "原文资源地址无效，请从本任务文档目录读取"); }
}
