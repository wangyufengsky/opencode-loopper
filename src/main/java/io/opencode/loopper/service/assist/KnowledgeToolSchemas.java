package io.opencode.loopper.service.assist;

import java.util.*;

/** Extended read tools use bounded, provider-visible schemas instead of unconstrained object inputs. */
final class KnowledgeToolSchemas {
    private KnowledgeToolSchemas() { }
    static List<AssistToolCatalog.Tool> added() {
        var items = new LinkedHashMap<String,Object>();
        for (String key : List.of("sourceId", "path", "expectedSha")) items.put(key, string());
        for (String key : List.of("section", "startLine", "endLine", "offset", "textOffset")) items.put(key, Map.of("type", "integer"));
        return List.of(
                tool("inspect_knowledge_project", "分页查看冻结来源、实际顶层目录及常见构建/说明文件；不推断模块职责，后续按目录和原文确认。offset 每页 5 个来源。", Map.of("offset", Map.of("type", "integer", "minimum", 0)), List.of()),
                tool("list_knowledge_evidence", "分页列出当前知识会话或业务角色保存的历史证据；knowledge: 与 call: 分属各自拥有者。", Map.of("cursor", string()), List.of()),
                tool("read_knowledge_sources", "批量读取 1–5 个授权文件/文档片段，每项独立返回结果、版本和失败；只引用实际保存的证据。", Map.of("items", Map.of("type", "array", "minItems", 1, "maxItems", 5, "items", Map.of("type", "object", "properties", items, "required", List.of("sourceId"), "additionalProperties", false))), List.of("items")),
                tool("find_knowledge_symbol", "在授权代码中查找标识符的逐条词法候选；包括定义、调用、注释和字符串，必须读原文确认，不声明语义引用图。", Map.of("symbol", Map.of("type", "string", "minLength", 1, "maxLength", 200), "path", string(), "cursor", string(), "sourceIds", Map.of("type", "array", "minItems", 1, "maxItems", 100, "items", string())), List.of("symbol")),
                git("search_knowledge_git_content", "按完整 commit SHA 搜索该版本文件正文，逐条匹配、来源及原文参数；只读本地对象。", false, false),
                git("search_knowledge_git_patches", "按 baseCommit..commit 搜索提交差异文本，包含头部、增删行和上下文；path 筛选涉及该路径的提交。", true, false),
                git("compare_knowledge_git_versions", "比较两个完整 SHA 的授权路径内容；最多 50 个可读文件，按差异文本行分页读取。", true, true));
    }
    static AssistToolCatalog.Tool enhance(AssistToolCatalog.Tool tool) {
        if (!Set.of("browse_knowledge_source", "search_knowledge", "search_project_knowledge").contains(tool.name())) return tool;
        @SuppressWarnings("unchecked") var original = (Map<String,Object>)tool.schema().get("properties");
        var properties = new LinkedHashMap<>(original);
        if (tool.name().equals("browse_knowledge_source")) {
            properties.put("depth", Map.of("type", "integer", "minimum", 1, "maximum", 12)); properties.put("recursive", Map.of("type", "boolean"));
            properties.put("entryType", Map.of("type", "string", "enum", List.of("all", "files", "directories")));
            properties.put("extensions", Map.of("type", "array", "maxItems", 20, "items", Map.of("type", "string", "pattern", "^[a-zA-Z0-9]{1,16}$")));
            properties.put("pathPattern", Map.of("type", "string", "maxLength", 200, "description", "来源内路径模式：* 不跨目录，** 可跨目录，? 匹配一个字符"));
        } else {
            properties.put("resultMode", Map.of("type", "string", "enum", List.of("files", "occurrences"), "description", "files 按文件/分段定位，occurrences 逐条正文命中并可继续同文件内分页"));
            properties.put("mode", Map.of("type", "string", "enum", List.of("AUTO", "EXACT", "PHRASE", "FIELD")));
            properties.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 200));
        }
        var schema = new LinkedHashMap<>(tool.schema()); schema.put("properties", properties);
        return new AssistToolCatalog.Tool(tool.name(), tool.description(), false, schema);
    }
    private static AssistToolCatalog.Tool git(String name, String description, boolean base, boolean compare) {
        var properties = new LinkedHashMap<String,Object>(); properties.put("sourceId", string()); properties.put("path", string());
        var sha = Map.of("type", "string", "pattern", "^([a-f0-9]{40}|[a-f0-9]{64})$"); properties.put("commit", sha);
        var required = new ArrayList<>(List.of("sourceId", "commit"));
        if (base) { properties.put("baseCommit", sha); required.add("baseCommit"); }
        if (compare) { properties.put("startLine", Map.of("type", "integer", "minimum", 1)); properties.put("endLine", Map.of("type", "integer", "minimum", 1)); }
        else { properties.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 200)); properties.put("cursor", string()); required.add("query"); }
        return tool(name, description, properties, required);
    }
    private static Map<String,Object> string() { return Map.of("type", "string"); }
    private static AssistToolCatalog.Tool tool(String name, String description, Map<String,Object> input, List<String> required) {
        var properties = new LinkedHashMap<>(input); properties.put("scope", string()); var keys = new ArrayList<>(required); keys.add("scope");
        return new AssistToolCatalog.Tool(name, description, false, Map.of("type", "object", "properties", properties, "required", keys, "additionalProperties", false));
    }
}
