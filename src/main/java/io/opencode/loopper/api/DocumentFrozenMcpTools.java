package io.opencode.loopper.api;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.roles.InternalRoleToolAuthority;
import java.util.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Static source tools expose only server-frozen records through an active role run. */
final class DocumentFrozenMcpTools {
    private DocumentFrozenMcpTools() { }
    static List<McpServerFeatures.SyncToolSpecification> specifications(DocumentFrozenReadService reads,
            DocumentReviewContextService context, InternalRoleToolAuthority authority, ObjectMapper json) {
        return List.of(spec("list_requirement_assessments", "List immutable assessment metadata from this generation for cross-batch consistency review", reads, context, authority, json,
                        fields("runId", text(), "after", integer(-1, 100000), "limit", integer(1, 100))),
                spec("read_requirement_assessment", "Read one completed bounded assessment by its expected result hash", reads, context, authority, json,
                        fields("runId", text(), "ordinal", integer(0, 100000), "expectedSha256", text())),
                spec("list_requirement_documents", "List all frozen uploaded documents and extraction limits", reads, context, authority, json,
                        fields("runId", text())),
                spec("list_document_sections", "List immutable section titles and hashes from a frozen document", reads, context, authority, json,
                        fields("runId", text(), "fileId", text(), "after", integer(-1, 2048), "limit", integer(1, 100))),
                spec("read_document_section", "Read one explicitly granted frozen document section", reads, context, authority, json,
                        fields("runId", text(), "fileId", text(), "section", integer(0, 2048), "expectedSha256", text())),
                spec("list_requirement_code", "List frozen source metadata, using the returned path cursor", reads, context, authority, json,
                        fields("runId", text(), "query", text(), "after", text(), "limit", integer(1, 100))),
                spec("read_requirement_code", "Read up to 200 lines and register exact immutable source evidence", reads, context, authority, json,
                        fields("runId", text(), "path", text(), "blobSha", text(), "startLine", integer(1, 10000000), "limit", integer(1, 200))),
                spec("search_requirement_code", "Search a literal in one frozen file; absence is not proof of missing implementation", reads, context, authority, json,
                        fields("runId", text(), "path", text(), "blobSha", text(), "query", text(), "afterLine", integer(0, 10000000))));
    }
    private static McpServerFeatures.SyncToolSpecification spec(String name, String description,
            DocumentFrozenReadService reads, DocumentReviewContextService context,
            InternalRoleToolAuthority authority, ObjectMapper json, Map<String, Object> properties) {
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
                authority.require(reads.authorizedSession(id), name);
                Object value = switch (name) {
                    case "list_requirement_assessments" -> context.list(id, number(args, "after"), number(args, "limit"));
                    case "read_requirement_assessment" -> context.read(id, number(args, "ordinal"), string(args, "expectedSha256"));
                    case "list_requirement_documents" -> Map.of("documents", reads.documents(id));
                    case "list_document_sections" -> reads.sections(id, string(args, "fileId"), number(args, "after"), number(args, "limit"));
                    case "read_document_section" -> reads.section(id, string(args, "fileId"), number(args, "section"), string(args, "expectedSha256"));
                    case "list_requirement_code" -> reads.files(id, string(args, "query"), string(args, "after"), number(args, "limit"));
                    case "read_requirement_code" -> reads.read(id, string(args, "path"), string(args, "blobSha"), number(args, "startLine"), number(args, "limit"));
                    case "search_requirement_code" -> reads.search(id, string(args, "path"), string(args, "blobSha"), string(args, "query"), number(args, "afterLine"));
                    default -> throw invalid();
                };
                Map<String, Object> result = json.convertValue(value, new TypeReference<>() { });
                return result(json, result, false);
            } catch (BadRequestException invalid) {
                return result(json, Map.of("errorCode", invalid.code(), "detail", invalid.getMessage()), true);
            } catch (ConflictException stale) {
                return result(json, Map.of("errorCode", stale.code(), "detail", stale.getMessage()), true);
            } catch (RuntimeException unavailable) {
                return result(json, Map.of("errorCode", "DOCUMENT_FROZEN_READ_UNAVAILABLE", "detail", "冻结读取未成功，不得将缺失证据视为需求满足"), true);
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
    private static BadRequestException invalid() { return new BadRequestException("DOCUMENT_READ_PARAMETERS_INVALID", "冻结读取参数缺失或无效"); }
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
