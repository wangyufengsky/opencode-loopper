package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.assist.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Shared bounded reads. Callers supply an already-authorized frozen selection and own receipts. */
@Service
public final class KnowledgeReadOperations {
    private final KnowledgeReader reader;
    private final KnowledgeSearchService search;
    private final KnowledgeGit git;
    private final DatabaseQueryService databases;
    private final ObjectMapper json;
    public KnowledgeReadOperations(KnowledgeReader reader, KnowledgeSearchService search, KnowledgeGit git,
            DatabaseQueryService databases, ObjectMapper json) {
        this.reader = reader; this.search = search; this.git = git; this.databases = databases; this.json = json;
    }
    public Map<String,Object> read(String owner, KnowledgeSources.Selection selection, String name, Map<String,Object> args) {
        if (name.equals("list_knowledge_sources")) {
            var views = new ArrayList<>(selection.sources().stream().map(KnowledgeSources::view).toList());
            selection.connections().forEach(c -> views.add(new KnowledgeSources.View("database:" + c.id(), "DATABASE", c.name(), "READY", "", c.version())));
            return Map.of("sources", views);
        }
        if (name.equals("inspect_knowledge_project")) return metadata("PROJECT", "project", "授权项目概览", inspect(selection, args));
        if (name.equals("read_knowledge_sources")) return batch(owner, selection, args);
        if (name.equals("find_knowledge_symbol")) return symbols(owner, selection, args);
        if (name.equals("search_project_knowledge")) return search.search(owner, selection, KnowledgeSearchContracts.Request.from(args));
        if (name.equals("list_database_connections")) return Map.of("connections", selection.connections().stream()
                .map(c -> Map.of("id", c.id(), "name", c.name(), "type", c.config().type(), "schemas", c.config().schemas(), "version", c.version())).toList());
        if (Set.of("query_database_readonly", "inspect_database_schema").contains(name)) return database(selection, name, args);
        var source = selection.sources().stream().filter(s -> s.id().equals(text(args, "sourceId")))
                .findFirst().orElseThrow(() -> KnowledgeSources.bad("资料不属于当前流程的冻结来源"));
        if (name.contains("knowledge_git")) return git.call(owner, source, name, args);
        return switch (name) {
            case "browse_knowledge_source" -> browse(source, args);
            case "search_knowledge" -> singleSearch(owner, source, args);
            case "read_knowledge_source" -> reader.readRange(source, text(args, "path"), number(args, "section", -1), number(args, "startLine", 1),
                    number(args, "endLine", 0), text(args, "expectedSha"), number(args, "offset", 0), number(args, "textOffset", -1));
            default -> throw KnowledgeSources.bad("当前角色不支持此知识读取操作");
        };
    }
    private Map<String,Object> browse(KnowledgeSources.Bound source, Map<String,Object> args) {
        var listing = reader.browse(source, args);
        Map<String,Object> body = json.convertValue(listing, new TypeReference<>() { });
        body.put("items", listing.items().stream().map(entry -> {
            var item = new LinkedHashMap<String,Object>(); item.put("path", entry.path()); item.put("name", entry.name());
            item.put("directory", entry.directory()); item.put("bytes", entry.bytes());
            item.put(entry.directory() ? "browse" : "read", Map.of("tool", entry.directory() ? "browse_knowledge_source" : "read_knowledge_source",
                    "arguments", Map.of("sourceId", source.id(), "path", entry.path()))); return item;
        }).toList());
        body.put("pathBase", KnowledgeSources.view(source).pathBase());
        body.put("detail", listing.detail() + " 文件名 query 仅检索路径元数据；文件正文按 read 参数读取。code 含代码，documents/独立文档目录仅含支持的文档。");
        return metadata("DIRECTORY", source.id(), source.name(), body);
    }
    private Map<String,Object> metadata(String kind, String source, String name, Map<String,Object> data) {
        var result = new LinkedHashMap<>(data); result.put("kind", kind); result.put("sourceId", source); result.put("name", name);
        result.put("location", "采集时目录元数据"); result.put("path", ""); result.put("sha256", AssistFiles.sha(json.writeValueAsBytes(data))); return result;
    }
    private Map<String,Object> singleSearch(String owner, KnowledgeSources.Bound source, Map<String,Object> args) {
        var request = KnowledgeSearchContracts.Request.from(args);
        var query = new KnowledgeSearchQuery(request.query(), request.mode() == null ? "EXACT" : request.mode(), request.terms());
        return request.resultMode().equals("occurrences") ? reader.occurrences(owner, source, request.path(), query, request.cursor())
                : reader.search(source, request.path(), query, request.cursor());
    }
    private Map<String,Object> inspect(KnowledgeSources.Selection selection, Map<String,Object> args) {
        int offset = number(args, "offset", 0); if (offset < 0 || offset > selection.sources().size()) throw KnowledgeSources.bad("来源位置无效");
        var items = new ArrayList<Map<String,Object>>();
        for (var source : selection.sources().stream().skip(offset).limit(5).toList()) {
            var item = new LinkedHashMap<String,Object>(); item.put("source", KnowledgeSources.view(source));
            if (!source.kind().equals("GIT")) {
                try {
                    var listing = reader.browse(source, Map.of("depth", 1)); item.put("rootEntries", listing.items());
                    item.put("keyFiles", listing.items().stream().filter(e -> !e.directory() && e.name().matches("(?i)(pom\\.xml|package\\.json|build\\.gradle(\\.kts)?|settings\\.gradle(\\.kts)?|Makefile|README.*|AGENTS\\.md|.*\\.sln|.*\\.csproj|go\\.mod|Cargo\\.toml|pyproject\\.toml)")).toList());
                    var layout = reader.browse(source, Map.of("depth", 3, "entryType", "files"));
                    item.put("observedFiles", layout.items());
                    item.put("layoutNext", Map.of("tool", "browse_knowledge_source", "arguments", layout.nextCursor() == null
                            ? Map.of("sourceId", source.id(), "depth", 3, "entryType", "files")
                            : Map.of("sourceId", source.id(), "depth", 3, "entryType", "files", "cursor", layout.nextCursor())));
                    item.put("incomplete", listing.incomplete() || listing.nextCursor() != null || layout.incomplete() || layout.nextCursor() != null);
                    item.put("next", Map.of("tool", "browse_knowledge_source", "arguments", listing.nextCursor() == null ? Map.of("sourceId", source.id(), "depth", 2) : Map.of("sourceId", source.id(), "depth", 1, "cursor", listing.nextCursor())));
                } catch (AssistFailure failure) { item.put("error", Map.of("code", failure.code(), "detail", failure.getMessage())); item.put("incomplete", true); }
            } else item.put("next", Map.of("tool", "inspect_knowledge_git", "arguments", Map.of("sourceId", source.id())));
            items.add(item);
        }
        return Map.of("sources", items, "nextOffset", offset + 5 < selection.sources().size() ? offset + 5 : -1,
                "databases", selection.connections().stream().map(c -> Map.of("id", c.id(), "name", c.name())).toList(),
                "detail", "仅列实际观察到的来源顶层目录和常见构建/说明文件；不推断模块职责。observedFiles 是深度 3 内的有界实际文件清单，测试、入口和文档职责须继续 browse 后 read 核实。");
    }
    private Map<String,Object> batch(String owner, KnowledgeSources.Selection selection, Map<String,Object> args) {
        if (!(args.get("items") instanceof List<?> requests) || requests.isEmpty() || requests.size() > 5) throw KnowledgeSources.bad("批量读取需要 1–5 个 items");
        var results = new ArrayList<Map<String,Object>>();
        for (int i = 0; i < requests.size(); i++) {
            var item = new LinkedHashMap<String,Object>(); item.put("index", i);
            try {
                if (!(requests.get(i) instanceof Map<?,?> raw) || raw.keySet().stream().anyMatch(k -> !(k instanceof String) || !Set.of("sourceId", "path", "section", "startLine", "endLine", "expectedSha", "offset", "textOffset").contains(k))) throw KnowledgeSources.bad("批量元素只能包含原文读取参数");
                var input = new LinkedHashMap<String,Object>(); raw.forEach((k,v) -> input.put((String)k, v));
                item.put("result", read(owner, selection, "read_knowledge_source", input)); item.put("status", "SUCCEEDED");
            } catch (AssistFailure failure) { item.put("status", "FAILED"); item.put("error", Map.of("code", failure.code(), "detail", failure.getMessage())); }
            results.add(item);
        }
        return Map.of("items", results, "partial", results.stream().anyMatch(r -> r.get("status").equals("FAILED")));
    }
    private Map<String,Object> symbols(String owner, KnowledgeSources.Selection selection, Map<String,Object> args) {
        String symbol = text(args, "symbol"); if (symbol == null || !symbol.matches("[\\p{L}_$][\\p{L}\\p{N}_$]{0,199}")) throw KnowledgeSources.bad("symbol 必须是单个标识符");
        var request = new LinkedHashMap<>(args); request.put("query", symbol); request.put("mode", "FIELD"); request.put("resultMode", "occurrences");
        // Restrict symbol evidence to code. Lexical candidates deliberately make no AST/type-resolution claims.
        var code = new KnowledgeSources.Selection(selection.sources().stream().filter(s -> s.kind().equals("CODE")).toList(), List.of());
        var result = new LinkedHashMap<>(search.search(owner + ":symbols", code, KnowledgeSearchContracts.Request.from(request)));
        result.put("analysis", "LEXICAL_CANDIDATES"); result.put("detail", "标识符词法命中，包括声明、调用、注释和字符串；不证明类型绑定或完整引用关系。按 read 读取声明及上下文确认定义和使用。"); return result;
    }
    private Map<String,Object> database(KnowledgeSources.Selection selection, String name, Map<String,Object> args) {
        var bound = selection.connections().stream().filter(c -> c.id().equals(text(args, "connectionId"))).findFirst()
                .orElseThrow(() -> KnowledgeSources.bad("数据库未授权给当前流程"));
        boolean query = name.equals("query_database_readonly");
        var output = query ? databases.query(bound, text(args, "sql"))
                : databases.inspect(bound, text(args, "schema"), text(args, "table"), text(args, "kind"), number(args, "offset", 0));
        var result = new LinkedHashMap<>(output); result.put("kind", "DATABASE"); result.put("sourceId", "database:" + bound.id()); result.put("name", bound.name());
        result.put("location", query ? "只读查询" : Objects.toString(text(args, "schema"), "") + "." + Objects.toString(text(args, "table"), "结构"));
        result.put("sql", query ? text(args, "sql") : ""); result.put("configurationVersion", bound.version());
        result.put("sha256", AssistFiles.sha(json.writeValueAsBytes(output))); return result;
    }
    private static String text(Map<String,Object> args, String key) { if (args.get(key) == null) return null; if (!(args.get(key) instanceof String value)) throw KnowledgeSources.bad(key + " 必须是文本"); return value; }
    private static int number(Map<String,Object> args, String key, int fallback) { if (args.get(key) == null) return fallback; if (!(args.get(key) instanceof Number value) || value.doubleValue() != value.intValue()) throw KnowledgeSources.bad(key + " 必须是整数"); return value.intValue(); }
}
