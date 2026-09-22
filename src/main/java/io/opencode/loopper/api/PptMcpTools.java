package io.opencode.loopper.api;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.runtime.PptAgentProfile;
import io.opencode.loopper.service.ppt.agent.PptAgentTools;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.domain.SessionFailure;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** PPT tools share the private generation transport but own their authorization and payload contracts. */
final class PptMcpTools {
    private PptMcpTools() { }
    static List<McpServerFeatures.SyncToolSpecification> specifications(PptAgentTools service, ObjectMapper json) {
        return PptAgentProfile.TOOLS.stream().map(name -> {
            var tool = McpSchema.Tool.builder(name, schema(name)).description(description(name))
                    .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(!writes(name)).destructiveHint(false)
                            .idempotentHint(true).openWorldHint(false).build()).build();
            return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
                try {
                    Object result = service.call(name, request.arguments());
                    Map<String, Object> response = Map.of("result", result);
                    return McpSchema.CallToolResult.builder().addTextContent(json.writeValueAsString(response))
                            .structuredContent(response).isError(false).build();
                } catch (BadRequestException e) { return error(json, e.code(), e.getMessage(), "FIX_AND_RESUBMIT"); }
                catch (ConflictException e) { return error(json, e.code(), e.getMessage(), "READ_CONTEXT_AND_RETRY_OR_STOP"); }
                catch (io.opencode.loopper.ppt.PptFailure e) { return error(json, e.code(), e.getMessage(), "FIX_AND_RESUBMIT"); }
                catch (SessionFailure e) { return error(json, e.code(), e.getMessage(), "STOP_AND_WAIT_FOR_RECOVERY"); }
                catch (RuntimeException e) { return error(json, "PPT_TOOL_FAILED", "PPT 工具未完成，请读取作品与原请求回执，不要盲目重复操作", "READ_CONTEXT_AND_RETRY_OR_STOP"); }
            }).build();
        }).toList();
    }
    private static Map<String,Object> schema(String name) {
        Map<String,Object> args = new LinkedHashMap<>();
        if (writes(name)) args.put("idempotencyKey", Map.of("type", "string", "minLength", 8, "maxLength", 128));
        if (Set.of("ppt_apply_operations", "ppt_submit_plan").contains(name)) args.put("expectedRevision", Map.of("type", "integer", "minimum", 0));
        if (name.equals("ppt_request_input")) { args.put("prompt", Map.of("type", "string")); args.put("options", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 6)); }
        switch (name) {
            case "ppt_apply_operations" -> args.put("operations", Map.of("type", "array", "minItems", 1, "maxItems", 100, "items", Map.of("type", "object")));
            case "ppt_submit_plan" -> args.put("plan", Map.of("type", "object"));
            case "ppt_get_context", "ppt_check_layout", "ppt_render_preview", "ppt_export" -> {
                args.put("revision", Map.of("type", "integer", "minimum", 0)); args.put("slideId", Map.of("type", "string"));
            }
            case "ppt_read_source" -> {
                args.put("sourceId", Map.of("type", "string")); args.put("sectionId", Map.of("type", "string"));
                args.put("offset", Map.of("type", "integer", "minimum", 0)); args.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 12000));
            }
            case "ppt_get_job" -> args.put("jobId", Map.of("type", "string"));
            case "ppt_measure_text" -> {
                args.put("revision", Map.of("type", "integer", "minimum", 0)); args.put("element", Map.of("type", "object"));
                args.put("slideId", Map.of("type", "string")); args.put("elementId", Map.of("type", "string"));
            }
            default -> { }
        }
        var required = new ArrayList<String>(); if (writes(name)) required.add("idempotencyKey");
        if (name.equals("ppt_request_input")) required.add("prompt");
        if (name.equals("ppt_apply_operations")) required.add("operations");
        if (name.equals("ppt_submit_plan")) required.add("plan");
        if (name.equals("ppt_read_source")) required.addAll(List.of("sourceId", "sectionId"));
        if (name.equals("ppt_get_job")) required.add("jobId");
        if (Set.of("ppt_render_preview", "ppt_export").contains(name)) required.add("revision");
        if (Set.of("ppt_apply_operations", "ppt_submit_plan").contains(name)) required.add("expectedRevision");
        return Map.of("type", "object", "additionalProperties", false, "required", List.of("scope", "runId", "documentId", "args"),
                "properties", Map.of("scope", Map.of("type", "string", "description", "Outbound runtime grant; never copy into args or artifacts"),
                        "runId", Map.of("type", "string"), "documentId", Map.of("type", "string"),
                        "args", Map.of("type", "object", "properties", args, "required", required,
                                "description", "Tool parameters; query ppt_get_capabilities for object, layout and operation shapes")));
    }
    private static boolean writes(String name) { return Set.of("ppt_request_input", "ppt_submit_plan", "ppt_apply_operations", "ppt_render_preview", "ppt_export").contains(name); }
    private static String description(String name) {
        return switch (name) {
            case "ppt_get_context" -> "Read current PPT phase, revision, plans and bounded pages; pass slideId to inspect a selected page.";
            case "ppt_read_source" -> "Read an explicitly selected document source using sourceId, sectionId and bounded offset/limit.";
            case "ppt_get_capabilities" -> "Read allowed objects, exact operation schemas, themes, layouts, fonts and current phase permissions before editing.";
            case "ppt_request_input" -> "Save one question with prompt and optional options, then STOP this model turn. A user reply resumes safely.";
            case "ppt_submit_plan" -> "Submit a complete plan candidate in args.plan, with expectedRevision/idempotencyKey. Does not confirm the design.";
            case "ppt_apply_operations" -> "Atomically edit pages/objects using args.operations, expectedRevision/idempotencyKey. Query capabilities first; preserve locked content.";
            case "ppt_measure_text" -> "Measure a supplied element object or existing slideId/elementId; returns wrapping and required height.";
            case "ppt_check_layout" -> "Check a saved revision for bounds, overflow, assets and unintended overlap; optional slideId.";
            case "ppt_render_preview" -> "Queue PNG previews for a frozen revision and optional slideId; returns job identity. Poll ppt_get_job.";
            case "ppt_get_job" -> "Read exact jobId and frozen revision results; completion is server-owned.";
            case "ppt_export" -> "Queue a checked editable PPTX export of revision with idempotencyKey in review/export phase; poll jobId.";
            default -> throw new IllegalArgumentException("Unknown PPT tool");
        };
    }
    private static McpSchema.CallToolResult error(ObjectMapper json, String code, String detail, String action) {
        var result = Map.<String,Object>of("errorCode", code, "detail", io.opencode.loopper.service.assist.AssistRedaction.text(detail),
                "action", action, "repairHint", "Read the named field or object in ppt_get_context / ppt_get_capabilities, then correct the same tool call. Never invent object IDs.");
        return McpSchema.CallToolResult.builder().addTextContent(json.writeValueAsString(result)).structuredContent(result).isError(true).build();
    }
}
