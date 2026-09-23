package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.assist.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import static io.opencode.loopper.service.knowledge.KnowledgeSearchContracts.*;

/** One bounded page of file contents or authorized JDBC metadata, with original-read coordinates. */
@Service
public class KnowledgeSearchScanner {
    private final KnowledgeReader reader;
    private final DatabaseQueryService databases;
    private final ObjectMapper json;
    public KnowledgeSearchScanner(KnowledgeReader reader, DatabaseQueryService databases, ObjectMapper json) {
        this.reader = reader; this.databases = databases; this.json = json;
    }
    @SuppressWarnings("unchecked")
    public Chunk files(KnowledgeSources.Bound source, KnowledgeSearchQuery query, String path, String cursor) {
        var result = reader.search(source, path, query, cursor); String now = Instant.now().toString();
        var matches = ((List<Map<String,Object>>) result.get("matches")).stream().map(hit -> {
            var row = new LinkedHashMap<>(hit); var args = new LinkedHashMap<String,Object>();
            for (String key : List.of("sourceId", "path", "section", "startLine", "textOffset")) if (hit.containsKey(key)) args.put(key, hit.get(key));
            args.put("expectedSha", hit.get("sha256"));
            row.put("sourceName", source.name()); row.put("collectedAt", now);
            row.put("read", Map.of("tool", "read_knowledge_source", "arguments", args)); return (Map<String,Object>) row;
        }).toList();
        return new Chunk(matches, (String) result.get("nextCursor"), Boolean.TRUE.equals(result.get("incomplete")),
                (List<String>) result.get("limitations"), ((Number) result.get("examinedFiles")).intValue());
    }
    @SuppressWarnings("unchecked")
    public Chunk database(DatabaseConnectionService.Bound source, KnowledgeSearchQuery query, String cursor) {
        String[] position = cursor == null ? new String[]{"0", "columns", "0"} : cursor.split(":");
        int index = Integer.parseInt(position[0]), offset = Integer.parseInt(position[2]); String phase = position[1];
        var schemas = source.config().schemas(); if (index >= schemas.size()) return new Chunk(List.of(), null, false, List.of(), 0);
        String schema = schemas.get(index);
        var data = phase.equals("columns") ? databases.searchColumns(source, schema, offset) : databases.inspect(source, schema, null, "tables", offset);
        var columns = (List<Map<String,Object>>) data.get("columns"); var rows = (List<List<Object>>) data.get("rows");
        List<Map<String,Object>> matches = new ArrayList<>(); List<String> limitations = new ArrayList<>();
        for (var values : rows) {
            var row = new LinkedHashMap<String,Object>();
            for (int i = 0; i < Math.min(columns.size(), values.size()); i++) row.put(Objects.toString(columns.get(i).get("name")).toUpperCase(Locale.ROOT), values.get(i));
            String table = value(row, "TABLE_NAME"), column = value(row, "COLUMN_NAME"), remarks = value(row, "REMARKS");
            var hit = query.locate(String.join("\n", table, column, remarks), column.isBlank() ? table : column);
            if (hit == null) continue;
            if (!table.matches("[\\p{L}\\p{N}_$-]{1,128}")) { limitations.add("命中表名超出当前结构读取支持范围，部分结果未返回"); continue; }
            String location = schema + "." + table + (column.isBlank() ? "" : "." + column);
            var match = new LinkedHashMap<String,Object>();
            match.put("sourceId", "database:" + source.id()); match.put("sourceName", source.name()); match.put("kind", "DATABASE");
            match.put("name", column.isBlank() ? table : column); match.put("path", location); match.put("location", location);
            match.put("schema", schema); match.put("table", table); match.put("column", column);
            match.put("resourceKey", source.id() + ":" + location); match.put("sha256", AssistFiles.sha(json.writeValueAsBytes(row)));
            match.put("snippet", excerpt(location + " " + value(row, "TYPE_NAME") + " " + remarks));
            match.put("score", hit.score()); match.put("matchType", hit.matchType()); match.put("matchedTerm", hit.matchedTerm());
            match.put("configurationVersion", source.version()); match.put("collectedAt", data.get("collectedAt"));
            int ordinal = row.get("ORDINAL_POSITION") instanceof Number n ? n.intValue() : 1;
            int readOffset = column.isBlank() ? 0 : Math.max(0, (ordinal - 1) / 100 * 100);
            if (readOffset > 10000) { limitations.add("部分字段超出结构读取游标上限"); continue; }
            match.put("read", Map.of("tool", "inspect_database_schema", "arguments", Map.of("connectionId", source.id(), "schema", schema,
                    "table", table, "kind", column.isBlank() ? "tables" : "columns", "offset", readOffset)));
            matches.add(match);
        }
        int nextOffset = data.get("nextOffset") instanceof Number n ? n.intValue() : -1;
        String next;
        if (nextOffset > offset && nextOffset <= 10000) next = index + ":" + phase + ":" + nextOffset;
        else {
            if (nextOffset >= 0) limitations.add("结构检索已达到单个 schema 的分页边界，请通过结构工具缩小到具体表");
            next = phase.equals("columns") ? index + ":tables:0" : index + 1 < schemas.size() ? (index + 1) + ":columns:0" : null;
        }
        if (Boolean.TRUE.equals(data.get("truncated")) && rows.isEmpty()) limitations.add("驱动返回的结构行超出大小边界，未能完整读取");
        return new Chunk(matches, next, next != null || !limitations.isEmpty(), limitations.stream().distinct().limit(5).toList(), rows.size());
    }
    private static String value(Map<String,Object> row, String key) { return row.get(key) instanceof String s ? s : ""; }
    private static String excerpt(String text) { return text.substring(0, Math.min(400, text.length())); }
}
