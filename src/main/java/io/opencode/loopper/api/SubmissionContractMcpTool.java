package io.opencode.loopper.api;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import io.opencode.loopper.service.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;

/** Describes the actual registered schema, never a separately maintained sample contract. */
final class SubmissionContractMcpTool {
    private SubmissionContractMcpTool() { }
    static McpServerFeatures.SyncToolSpecification specification(SubmissionContractReadService reads,
            List<McpServerFeatures.SyncToolSpecification> registered, ObjectMapper json) {
        var catalog = registered.stream().map(McpServerFeatures.SyncToolSpecification::tool)
                .collect(Collectors.toUnmodifiableMap(McpSchema.Tool::name, tool -> tool));
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of(
                "runId", Map.of("type", "string", "minLength", 1, "maxLength", 128),
                "pointer", Map.of("type", "string", "maxLength", 512, "description", "JSON Pointer into inputSchema; empty returns the complete schema")),
                "required", List.of("runId", "pointer"), "additionalProperties", false);
        var tool = McpSchema.Tool.builder(InternalMcpContractCatalog.DESCRIBE_TOOL, schema)
                .description("Before composing or repairing a submission, read its authoritative parameter schema, required fields, enums and current revision. Use the same runId as submission; pointer='' reads the full schema.")
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            try {
                var args = request.arguments();
                if (args == null || !args.keySet().equals(java.util.Set.of("runId", "pointer"))
                        || !(args.get("runId") instanceof String runId) || runId.isBlank() || runId.length() > 128
                        || !(args.get("pointer") instanceof String pointer) || pointer.length() > 512
                        || !pointer.isEmpty() && !pointer.startsWith("/"))
                    throw new BadRequestException("CONTRACT_QUERY_INVALID", "请提供 runId 和 pointer；查询完整结构时 pointer 填空字符串");
                var contract = reads.describe(runId);
                var target = catalog.get(contract.toolName());
                if (target == null) throw new ConflictException("CONTRACT_UNAVAILABLE", "当前角色的提交合同不可用");
                var selected = json.valueToTree(target.inputSchema()).at(pointer);
                if (selected.isMissingNode()) throw new BadRequestException("CONTRACT_POINTER_INVALID", "该参数路径不存在，请先查询完整结构");
                reads.describe(runId);
                return result(json, Map.of("contract", contract, "description", target.description(), "pointer", pointer,
                        "inputSchema", selected, "guidance", List.of(
                                "required 列出的字段必须提供；空数组与 null 不可互换；additionalProperties=false 表示不能新增字段。",
                                "expectedSubmissionRevision 使用本次返回值；每个新候选使用新 idempotencyKey，响应未知时精确重放原请求。",
                                "结构有效不代表业务通过；依据冻结任务原文和证据填写，提交失败后按 problems 的字段路径修正。")), false);
            } catch (BadRequestException invalid) { return result(json, Map.of("code", invalid.code(), "detail", invalid.getMessage()), true);
            } catch (ConflictException stale) { return result(json, Map.of("code", stale.code(), "detail", stale.getMessage()), true);
            } catch (RuntimeException unavailable) { return result(json, Map.of("code", "CONTRACT_UNAVAILABLE", "detail", "合同暂不可用，请核对当前运行身份后重试"), true); }
        }).build();
    }
    private static McpSchema.CallToolResult result(ObjectMapper json, Map<String, Object> value, boolean error) {
        return McpSchema.CallToolResult.builder().structuredContent(value).addTextContent(json.writeValueAsString(value)).isError(error).build();
    }
}
