package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.assist.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Structured local directory, text and document reads shared by the UI and MCP. */
@Service
public class KnowledgeReader {
    private final KnowledgeSources sources;
    private final KnowledgeDocumentCache documents;
    public KnowledgeReader(KnowledgeSources sources, KnowledgeDocumentCache documents) { this.sources = sources; this.documents = documents; }
    public KnowledgeFiles.Listing browse(KnowledgeSources.Bound source, String path, String query, String cursor) {
        requireFileSource(source);
        if (source.kind().equals("UPLOAD")) return new KnowledgeFiles.Listing(List.of(new KnowledgeFiles.Entry("", source.name(), false, 0)), null, false, "");
        return KnowledgeFiles.list(source.path(), path == null ? "" : path, !source.kind().equals("CODE"), query, cursor, query != null && !query.isBlank());
    }
    public Map<String,Object> read(KnowledgeSources.Bound source, String relative, int section, int startLine, String expected) {
        return read(source, relative, section, startLine, expected, 0);
    }
    public Map<String,Object> read(KnowledgeSources.Bound source, String relative, int section, int startLine, String expected, int offset) {
        requireFileSource(source);
        if (offset < 0 || offset > 10000) throw KnowledgeSources.bad("文档目录游标无效");
        Path path = sources.path(source, relative); String name = source.kind().equals("UPLOAD") ? source.name() : path.getFileName().toString();
        boolean document = !source.kind().equals("CODE") || KnowledgeFiles.DOCUMENTS.contains(KnowledgeFiles.extension(name));
        if (document && !KnowledgeFiles.DOCUMENTS.contains(KnowledgeFiles.extension(name))) throw KnowledgeSources.bad("此来源仅允许读取支持的项目文档");
        String sha; Map<String,Object> body = new LinkedHashMap<>();
        if (document) {
            var parsed = documents.parse(name, sources.read(source, relative, 20 * 1024 * 1024)); sha = parsed.sha256();
            body.put("kind", "DOCUMENT"); body.put("limitations", parsed.document().limitations());
            var sections = parsed.document().sections();
            if (section < 0) {
                body.put("sections", sections.stream().skip(offset).limit(100).map(s -> Map.of("section", Integer.parseInt(s.id()), "title", s.title())).toList());
                body.put("sectionCount", sections.size()); body.put("nextOffset", offset + 100 < sections.size() ? offset + 100 : -1); body.put("location", "文档目录"); body.put("text", "");
            } else {
                if (section >= sections.size()) throw KnowledgeSources.bad("文档分段不存在，请重新读取目录");
                body.put("text", sections.get(section).markdown());
                boolean markdown = Set.of("md", "markdown").contains(parsed.document().format());
                int first = markdown ? 1 + (int) sections.stream().limit(section).map(s -> s.markdown()).reduce("", String::concat).chars().filter(c -> c == '\n').count() : 1;
                body.put("startLine", first); body.put("endLine", first + sections.get(section).markdown().split("\\n", -1).length - 1);
                body.put("lineBasis", markdown ? "原文件行号" : "本段解析文本行号"); body.put("format", parsed.document().format()); body.put("location", sections.get(section).title() + " · 第 " + (section + 1) + " 段");
                body.put("section", section); body.put("nextSection", section + 1 < sections.size() ? section + 1 : -1);
            }
        } else {
            String content = KnowledgeFiles.text(sources.read(source, relative, 1024 * 1024)); sha = AssistFiles.sha(content.getBytes(StandardCharsets.UTF_8));
            String[] lines = content.split("\n", -1);
            if (startLine < 1 || startLine > lines.length) throw KnowledgeSources.bad("代码起始行无效");
            int end = Math.min(lines.length, startLine + 199); StringBuilder text = new StringBuilder();
            for (int i = startLine - 1; i < end; i++) {
                if (text.length() + lines[i].length() + 1 > 12000) { end = i; break; }
                text.append(lines[i]).append('\n');
            }
            if (text.isEmpty() && end < startLine) throw KnowledgeSources.bad("单行文本过长，无法作为代码片段读取");
            body.put("kind", "CODE"); body.put("text", text.toString()); body.put("startLine", startLine); body.put("endLine", end);
            body.put("totalLines", lines.length); body.put("location", relative + " · 第 " + startLine + "–" + end + " 行");
            body.put("nextLine", end < lines.length ? end + 1 : -1);
        }
        if (expected != null && !expected.isBlank() && !sha.equals(expected)) throw new AssistFailure("KNOWLEDGE_SOURCE_CHANGED", "源文件已变化，请重新检索并读取当前版本");
        if (source.kind().equals("UPLOAD") && !sha.equals(source.sha256())) throw KnowledgeSources.bad("上传原件校验失败，请重新上传");
        body.put("sourceId", source.id()); body.put("path", relative == null ? "" : relative); body.put("name", name); body.put("sha256", sha);
        body.put("versionLabel", "当前本地文件 · " + sha.substring(0, 12));
        return body;
    }
    public Map<String,Object> readRange(KnowledgeSources.Bound source, String relative, int section, int start, int end, String expected, int offset) {
        if (source.kind().equals("GIT")) throw KnowledgeSources.bad("请使用 Git 查询工具读取此来源");
        var body = new LinkedHashMap<>(read(source, relative, section, source.kind().equals("CODE") ? start : 1, expected, offset));
        if (end > 0 && body.get("text") instanceof String text && !text.isEmpty()) {
            int first = ((Number) body.getOrDefault("startLine", 1)).intValue();
            int last = ((Number) body.getOrDefault("endLine", first)).intValue();
            if (start < first || end < start || end > last) throw KnowledgeSources.bad("引用范围不在此片段中，请按返回行号读取");
            String[] lines = text.split("\\n", -1); body.put("text", String.join("\n", Arrays.copyOfRange(lines, start-first, end-first+1)));
            body.put("startLine", start); body.put("endLine", end);
        }
        return body;
    }
    public Map<String,Object> search(KnowledgeSources.Bound source, String relative, String query, String cursor) {
        return search(source, relative, new KnowledgeSearchQuery(query, "EXACT", List.of()), cursor);
    }
    public Map<String,Object> search(KnowledgeSources.Bound source, String relative, KnowledgeSearchQuery query, String cursor) {
        requireFileSource(source);
        var listing = source.kind().equals("UPLOAD") ? browse(source, "", "", null)
                : KnowledgeFiles.list(source.path(), relative == null ? "" : relative, !source.kind().equals("CODE"), "", cursor, true);
        List<Map<String,Object>> matches = new ArrayList<>(); List<String> limitations = new ArrayList<>();
        if (listing.incomplete()) limitations.add(listing.detail());
        long deadline = System.nanoTime() + 3_000_000_000L, bytes = 0; String next = listing.nextCursor();
        String last = cursor; int examined = 0;
        for (var entry : listing.items()) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline || bytes > 16 * 1024 * 1024 || matches.size() >= 30) { next = last; limitations.add("检索达到单次边界，请继续下一页或缩小目录"); break; }
            if (entry.directory()) continue;
            try {
                Path path = sources.path(source, entry.path()); String name = source.kind().equals("UPLOAD") ? source.name() : entry.name();
                bytes += Files.size(path); examined++;
                if (KnowledgeFiles.DOCUMENTS.contains(KnowledgeFiles.extension(name))) {
                    var parsed = documents.parse(name, sources.read(source, entry.path(), 20 * 1024 * 1024));
                    for (var s : parsed.document().sections()) {
                        if (matches.size() >= 30) { limitations.add(name + "：仅返回前 30 处匹配，更多内容请按文档目录逐段读取"); break; }
                        var hit = query.locate(s.markdown(), name);
                        if (hit != null) matches.add(scored(Map.of("resourceKey", AssistFiles.sha(path.toString().getBytes(StandardCharsets.UTF_8)), "sourceId", source.id(), "path", entry.path(), "name", name,
                                "section", Integer.parseInt(s.id()), "location", s.title(), "sha256", parsed.sha256(), "snippet", snippet(s.markdown(), hit.index())), hit, "DOCUMENT"));
                    }
                } else if (source.kind().equals("CODE")) {
                    String text = KnowledgeFiles.text(sources.read(source, entry.path(), 1024 * 1024)); var hit = query.locate(text, entry.path());
                    if (hit != null) matches.add(scored(Map.of("resourceKey", AssistFiles.sha(path.toString().getBytes(StandardCharsets.UTF_8)), "sourceId", source.id(), "path", entry.path(), "name", name,
                            "startLine", 1 + (int) text.substring(0, hit.index()).chars().filter(c -> c == '\n').count(),
                            "sha256", AssistFiles.sha(text.getBytes(StandardCharsets.UTF_8)), "snippet", snippet(text, hit.index())), hit, "CODE"));
                }
            } catch (Exception unavailable) { if (limitations.size() < 10) limitations.add(entry.name() + "：" + (unavailable instanceof AssistFailure ? unavailable.getMessage() : "无法读取")); }
            last = entry.path();
        }
        var result = new LinkedHashMap<String,Object>(); result.put("matches", matches); result.put("nextCursor", next);
        result.put("incomplete", listing.incomplete() || next != null || !limitations.isEmpty()); result.put("limitations", limitations);
        result.put("examinedFiles", examined); result.put("detail", "检索片段只用于定位；引用前请读取原文。无命中不是功能不存在的证明。"); return result;
    }
    private static Map<String,Object> scored(Map<String,Object> match, KnowledgeSearchQuery.Hit hit, String kind) {
        var result = new LinkedHashMap<>(match); result.put("score", hit.score()); result.put("matchType", hit.matchType());
        result.put("matchedTerm", hit.matchedTerm()); result.put("kind", kind); return result;
    }
    private static void requireFileSource(KnowledgeSources.Bound source) {
        if (!Set.of("CODE", "DOCUMENTS", "DIRECTORY", "UPLOAD").contains(source.kind())) throw KnowledgeSources.bad("此来源不能通过文件工具读取，请使用对应的 Git 或数据库工具");
    }
    private static String snippet(String text, int index) { return text.substring(Math.max(0, index - 60), Math.min(text.length(), index + 240)); }
}
