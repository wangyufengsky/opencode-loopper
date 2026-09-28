package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.assist.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Position cursors retain no file contents and bind owner, query, source and the partially read file SHA. */
final class KnowledgeOccurrences {
    private record Position(Object identity, String directoryCursor, String file, String sha, int from, long created) { }
    private final Map<String,Position> cursors = new LinkedHashMap<>();
    Map<String,Object> search(String owner, KnowledgeSources.Bound source, String path, KnowledgeSearchQuery query,
            String cursor, KnowledgeSources sources, KnowledgeDocumentCache documents) {
        Object identity = List.of(owner, source, Objects.toString(path, ""), query.query(), query.mode(), query.expandedTerms());
        Position position = position(identity, cursor);
        var listing = source.kind().equals("UPLOAD")
                ? new KnowledgeFiles.Listing(List.of(new KnowledgeFiles.Entry("", source.name(), false, 0)), null, false, "")
                : KnowledgeFiles.list(source.path(), Objects.toString(path, ""), !source.kind().equals("CODE"), "", position.directoryCursor(), true);
        var matches = new ArrayList<Map<String,Object>>(); var limitations = new ArrayList<String>();
        if (listing.incomplete()) limitations.add(listing.detail());
        long deadline = System.nanoTime() + 3_000_000_000L, bytes = 0; int processed = 0, examined = 0; String next = null;
        for (var entry : listing.items()) {
            if (matches.size() >= 30 || System.nanoTime() > deadline || Thread.currentThread().isInterrupted() || bytes > 16 * 1024 * 1024) {
                next = save(identity, KnowledgeDirectoryPages.resume(listing, processed), null, null, 0); break;
            }
            processed++; if (entry.directory()) continue;
            boolean resumed = position.file() != null && position.file().equals(entry.path());
            try {
                var file = sources.path(source, entry.path()); bytes += Files.size(file); examined++;
                String name = source.kind().equals("UPLOAD") ? source.name() : entry.name(), sha, kind;
                List<Map<String,Object>> rows; int from = resumed ? position.from() : 0, count = 31 - matches.size();
                if (KnowledgeFiles.DOCUMENTS.contains(KnowledgeFiles.extension(name))) {
                    var parsed = documents.parse(name, sources.read(source, entry.path(), 20 * 1024 * 1024)); sha = parsed.sha256(); kind = "DOCUMENT";
                    rows = new KnowledgeDocumentText(parsed.document()).occurrences(query, from, count);
                } else if (source.kind().equals("CODE")) {
                    byte[] raw = sources.read(source, entry.path(), 1024 * 1024); String text = KnowledgeFiles.text(raw); sha = AssistFiles.sha(raw); kind = "CODE";
                    rows = query.occurrences(text, from, count).stream().map(h -> hit(text, h, 0)).toList();
                } else continue;
                if (resumed && !Objects.equals(position.sha(), sha)) throw new AssistFailure("KNOWLEDGE_SOURCE_CHANGED", "源文件已变化，请从第一页重新检索");
                for (var row : rows) {
                    if (matches.size() >= 30) {
                        next = save(identity, KnowledgeDirectoryPages.resume(listing, processed - 1), entry.path(), sha, ((Number)row.get("matchOffset")).intValue()); break;
                    }
                    row.put("resourceKey", AssistFiles.sha(file.toString().getBytes(StandardCharsets.UTF_8)));
                    row.put("sourceId", source.id()); row.put("sourceName", source.name()); row.put("path", entry.path()); row.put("name", name); row.put("sha256", sha); row.put("kind", kind);
                    var args = new LinkedHashMap<String,Object>(); args.put("sourceId", source.id()); args.put("path", entry.path()); args.put("expectedSha", sha);
                    if (kind.equals("DOCUMENT")) { args.put("section", row.get("section")); args.put("textOffset", row.get("textOffset")); }
                    else { args.put("startLine", Math.max(1, ((Number)row.get("startLine")).intValue() - 2)); }
                    row.put("read", Map.of("tool", "read_knowledge_source", "arguments", args)); matches.add(row);
                }
                if (next != null) break;
            } catch (RuntimeException failure) {
                if (resumed) throw failure;
                if (limitations.size() < 10) limitations.add(entry.name() + "：" + (failure instanceof AssistFailure ? failure.getMessage() : "无法读取"));
            } catch (java.io.IOException failure) { if (limitations.size() < 10) limitations.add(entry.name() + "：无法读取"); }
        }
        if (next == null && listing.nextCursor() != null) next = save(identity, listing.nextCursor(), null, null, 0);
        var result = new LinkedHashMap<String,Object>(); result.put("matches", matches); result.put("nextCursor", next); result.put("examinedFiles", examined);
        result.put("incomplete", next != null || listing.incomplete() || !limitations.isEmpty()); result.put("limitations", limitations);
        result.put("resultMode", "occurrences"); result.put("detail", "逐条正文命中，不将文件名命中当作正文；匹配数只代表本页，引用前按 read 读取原文。"); return result;
    }
    private synchronized Position position(Object identity, String cursor) {
        cursors.values().removeIf(p -> System.nanoTime() - p.created() > 300_000_000_000L);
        if (cursor == null || cursor.isBlank()) return new Position(identity, null, null, null, 0, System.nanoTime());
        var value = cursors.get(cursor);
        if (value == null || !value.identity().equals(identity)) throw KnowledgeSources.bad("逐条检索游标已过期或不属于当前查询，请重新检索");
        return value;
    }
    private synchronized String save(Object identity, String directory, String file, String sha, int from) {
        if (directory == null && file == null) return null;
        while (cursors.size() >= 128) cursors.remove(cursors.keySet().iterator().next());
        String id = UUID.randomUUID().toString(); cursors.put(id, new Position(identity, directory, file, sha, from, System.nanoTime())); return id;
    }
    static Map<String,Object> hit(String text, KnowledgeSearchQuery.Hit hit, int lineOrigin) {
        int at = hit.index(), line = 1 + (int)text.substring(lineOrigin, at).chars().filter(c -> c == '\n').count();
        int start = Math.max(lineOrigin, at - 100), end = Math.min(text.length(), at + 300), boundary = text.indexOf('\0', at);
        if (boundary >= 0) end = Math.min(end, boundary);
        var row = new LinkedHashMap<String,Object>(); row.put("startLine", line); row.put("endLine", line + (int)text.substring(at, Math.min(text.length(), hit.endIndex())).chars().filter(c -> c == '\n').count()); row.put("matchOffset", at);
        row.put("column", at - text.lastIndexOf('\n', at - 1)); row.put("snippet", text.substring(start, end));
        row.put("score", hit.score()); row.put("matchType", hit.matchType()); row.put("matchedTerm", hit.matchedTerm()); return row;
    }
}
