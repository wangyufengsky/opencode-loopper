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
        if (name.equals("search_project_knowledge")) return search.search(owner, selection, KnowledgeSearchContracts.Request.from(args));
        if (name.equals("list_database_connections")) return Map.of("connections", selection.connections().stream()
                .map(c -> Map.of("id", c.id(), "name", c.name(), "type", c.config().type(), "schemas", c.config().schemas(), "version", c.version())).toList());
        if (Set.of("query_database_readonly", "inspect_database_schema").contains(name)) return database(selection, name, args);
        var source = selection.sources().stream().filter(s -> s.id().equals(text(args, "sourceId")))
                .findFirst().orElseThrow(() -> KnowledgeSources.bad("资料不属于当前流程的冻结来源"));
        if (name.contains("knowledge_git")) return git.call(owner, source, name, args);
        return switch (name) {
            case "browse_knowledge_source" -> json.convertValue(reader.browse(source, text(args, "path"), text(args, "query"), text(args, "cursor")), new TypeReference<>() { });
            case "search_knowledge" -> reader.search(source, text(args, "path"), text(args, "query"), text(args, "cursor"));
            case "read_knowledge_source" -> reader.readRange(source, text(args, "path"), number(args, "section", -1), number(args, "startLine", 1),
                    number(args, "endLine", 0), text(args, "expectedSha"), number(args, "offset", 0), number(args, "textOffset", -1));
            default -> throw KnowledgeSources.bad("当前角色不支持此知识读取操作");
        };
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
    private static String text(Map<String,Object> args, String key) { return args.get(key) instanceof String value ? value : null; }
    private static int number(Map<String,Object> args, String key, int fallback) { return args.get(key) instanceof Number value ? value.intValue() : fallback; }
}
